package vn.brightpath.scheduling.lesson;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LessonService {

    /** Postgres SQLSTATE for "exclusion constraint violated". */
    private static final String EXCLUSION_VIOLATION = "23P01";

    private final JdbcTemplate jdbc;
    private final LessonRepository lessons;
    private final Clock clock;

    public LessonService(JdbcTemplate jdbc, LessonRepository lessons, Clock clock) {
        this.jdbc = jdbc;
        this.lessons = lessons;
        this.clock = clock;
    }

    @Transactional
    public LessonView book(BookLessonRequest req) {
        return insertLesson(req, null, null);
    }

    /**
     * Moves a booked lesson. The original row becomes 'rescheduled' (slot released, still visible
     * to its tutor) and a new row is booked through the same checks as a fresh booking, pointing
     * back via replaces_lesson_id. If the new slot is rejected the whole move rolls back and the
     * original lesson is untouched.
     */
    @Transactional
    public LessonView reschedule(long lessonId, RescheduleLessonRequest req) {
        Instant oldStart = lockBookedLesson(lessonId);
        Instant now = clock.instant();
        if (!now.isBefore(oldStart)) {
            throw new RuleViolationException(HttpStatus.UNPROCESSABLE_CONTENT, "lesson_already_started",
                    "Lesson " + lessonId + " has already started and cannot be moved");
        }
        LessonView old = lessons.findById(lessonId);
        int oldDuration = (int) Duration.between(old.startsAt(), old.endsAt()).toMinutes();

        jdbc.update("UPDATE lesson SET status = 'rescheduled' WHERE id = ?", lessonId);
        lessons.recordChange(lessonId, "rescheduled", oldStart, now, req.reason());

        BookLessonRequest moved = new BookLessonRequest(
                old.studentId(),
                req.tutorId() != null ? req.tutorId() : old.tutorId(),
                req.roomId() != null ? req.roomId() : old.roomId(),
                req.startsAt(),
                req.durationMin() != null ? req.durationMin() : oldDuration);
        String why = "moved from lesson " + lessonId + (req.reason() == null ? "" : ": " + req.reason());
        return insertLesson(moved, lessonId, why);
    }

    /**
     * Cancels a booked lesson. The row stays (status 'cancelled') so the tutor can see it was
     * cancelled; the 4h rule is decided now and stored. Either way the slot is released.
     */
    @Transactional
    public LessonView cancel(long lessonId, String reason) {
        Instant start = lockBookedLesson(lessonId);
        Instant now = clock.instant();
        if (!now.isBefore(start)) {
            throw new RuleViolationException(HttpStatus.UNPROCESSABLE_CONTENT, "lesson_already_started",
                    "Lesson " + lessonId + " has already started; record a no-show instead");
        }
        jdbc.update("UPDATE lesson SET status = 'cancelled', cancelled_at = ?, late_cancel = ? WHERE id = ?",
                Timestamp.from(now), CenterRules.isLateCancel(start, now), lessonId);
        lessons.recordChange(lessonId, "cancelled", start, now, reason);
        return lessons.findById(lessonId);
    }

    /** The student did not come: the lesson keeps its slot and is charged; nothing is released. */
    @Transactional
    public LessonView markNoShow(long lessonId) {
        Instant start = lockBookedLesson(lessonId);
        Instant now = clock.instant();
        if (now.isBefore(start)) {
            throw new RuleViolationException(HttpStatus.UNPROCESSABLE_CONTENT, "lesson_not_started",
                    "Lesson " + lessonId + " has not started yet; cancel it instead");
        }
        jdbc.update("UPDATE lesson SET status = 'no_show' WHERE id = ?", lessonId);
        lessons.recordChange(lessonId, "no_show", start, now, null);
        return lessons.findById(lessonId);
    }

    private LessonView insertLesson(BookLessonRequest req, Long replacesLessonId, String changeReason) {
        if (!CenterRules.ALLOWED_DURATIONS_MIN.contains(req.durationMin())) {
            throw new RuleViolationException(HttpStatus.UNPROCESSABLE_CONTENT, "lesson_duration_60_or_90",
                    "A lesson is 60 or 90 minutes, got " + req.durationMin());
        }
        Instant start = req.startsAt().atZone(CenterRules.CENTER_ZONE).toInstant();
        Instant end = start.plusSeconds(60L * req.durationMin());
        if (start.isBefore(clock.instant())) {
            throw new RuleViolationException(HttpStatus.UNPROCESSABLE_CONTENT, "booking_in_the_past",
                    "Cannot book a lesson that starts before now (" + clock.instant().atZone(CenterRules.CENTER_ZONE) + ")");
        }
        LocalDate day = req.startsAt().toLocalDate();
        if (day.getDayOfWeek() == CenterRules.CLOSED_DAY) {
            throw new RuleViolationException(HttpStatus.UNPROCESSABLE_CONTENT, CenterRules.RULE_CLOSED_ON_MONDAY,
                    "The centre is closed on Monday, when the rooms are cleaned");
        }

        // Locking the tutor row serialises bookings per tutor, so two receptionists booking the
        // same tutor at once cannot both see "5 lessons today" and push the tutor to 7.
        lockTutor(req.tutorId());
        requireExists("room", req.roomId());
        requireExists("student", req.studentId());

        // The owner wants the 6-per-day limit enforced, so there is no override (see DECISIONS.md).
        int sameDay = lessons.countTutorLessonsOn(req.tutorId(), day);
        if (sameDay >= CenterRules.MAX_LESSONS_PER_TUTOR_PER_DAY) {
            throw new RuleViolationException(HttpStatus.UNPROCESSABLE_CONTENT, CenterRules.RULE_MAX_PER_DAY,
                    "Tutor " + req.tutorId() + " already has " + sameDay + " lessons on " + day);
        }

        List<Conflict> conflicts = lessons.findConflicts(req.studentId(), req.tutorId(), req.roomId(), start, end);
        if (!conflicts.isEmpty()) {
            throw clash(conflicts.get(0).rule(), conflicts);
        }

        try {
            long id = jdbc.queryForObject("""
                    INSERT INTO lesson (student_id, tutor_id, room_id, starts_at, ends_at, replaces_lesson_id)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class,
                    req.studentId(), req.tutorId(), req.roomId(), Timestamp.from(start), Timestamp.from(end),
                    replacesLessonId);
            // A booking made after the 16:00 lock is itself a change the tutor must be shown.
            lessons.recordChange(id, "booked", start, clock.instant(), changeReason);
            return lessons.findById(id);
        } catch (DataIntegrityViolationException e) {
            // Another booking for the same student or room committed between our check and insert.
            // The exclusion constraint caught it; the transaction is now aborted, so we cannot look
            // up the other lesson, only name the rule.
            String constraint = exclusionConstraintName(e);
            if (constraint == null) {
                throw e;
            }
            throw clash(constraint, List.of());
        }
    }

    /** Locks the lesson row (so two cancels cannot race) and returns its start; it must still be booked. */
    private Instant lockBookedLesson(long lessonId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, starts_at FROM lesson WHERE id = ? FOR UPDATE", lessonId);
        if (rows.isEmpty()) {
            throw notFound("lesson", lessonId);
        }
        String status = (String) rows.get(0).get("status");
        if (!"booked".equals(status)) {
            throw new RuleViolationException(HttpStatus.CONFLICT, "lesson_not_booked",
                    "Lesson " + lessonId + " is already " + status);
        }
        return ((Timestamp) rows.get(0).get("starts_at")).toInstant();
    }

    private void lockTutor(String tutorId) {
        List<String> found = jdbc.queryForList("SELECT id FROM tutor WHERE id = ? FOR UPDATE", String.class, tutorId);
        if (found.isEmpty()) {
            throw notFound("tutor", tutorId);
        }
    }

    private void requireExists(String table, Object id) {
        // table is a constant from this class, never user input
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM " + table + " WHERE id = ?)",
                Boolean.class, id);
        if (!Boolean.TRUE.equals(exists)) {
            throw notFound(table, id);
        }
    }

    private static RuleViolationException notFound(String what, Object id) {
        return new RuleViolationException(HttpStatus.NOT_FOUND, what + "_not_found", "No " + what + " with id " + id);
    }

    private static RuleViolationException clash(String rule, List<Conflict> conflicts) {
        return new RuleViolationException(HttpStatus.CONFLICT, rule,
                "Clashes with an existing lesson (" + rule + ")", conflicts);
    }

    private static String exclusionConstraintName(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof PSQLException p && EXCLUSION_VIOLATION.equals(p.getSQLState())
                    && p.getServerErrorMessage() != null) {
                return p.getServerErrorMessage().getConstraint();
            }
        }
        return null;
    }
}
