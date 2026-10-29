package vn.brightpath.scheduling.lesson;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

        // Locking the tutor row serialises bookings per tutor, so two receptionists booking the
        // same tutor at once cannot both see "5 lessons today" and push the tutor to 7.
        lockTutor(req.tutorId());
        requireExists("room", req.roomId());
        requireExists("student", req.studentId());

        List<String> softRuleBreaks = softRuleBreaks(req.tutorId(), req.startsAt().toLocalDate());
        boolean hasReason = req.overrideReason() != null && !req.overrideReason().isBlank();
        if (!softRuleBreaks.isEmpty() && !hasReason) {
            throw new RuleViolationException(HttpStatus.UNPROCESSABLE_CONTENT, softRuleBreaks.get(0),
                    "Breaks center rules " + softRuleBreaks + "; resend with overrideReason to book anyway",
                    softRuleBreaks);
        }

        List<Conflict> conflicts = lessons.findConflicts(req.studentId(), req.tutorId(), req.roomId(), start, end);
        if (!conflicts.isEmpty()) {
            throw clash(conflicts.get(0).rule(), conflicts);
        }

        try {
            long id = jdbc.queryForObject("""
                    INSERT INTO lesson (student_id, tutor_id, room_id, starts_at, ends_at, override_reason)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class,
                    req.studentId(), req.tutorId(), req.roomId(), Timestamp.from(start), Timestamp.from(end),
                    softRuleBreaks.isEmpty() ? null : req.overrideReason());
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

    private List<String> softRuleBreaks(String tutorId, LocalDate day) {
        List<String> breaks = new ArrayList<>();
        if (day.getDayOfWeek() == CenterRules.CLOSED_DAY) {
            breaks.add(CenterRules.RULE_CLOSED_ON_MONDAY);
        }
        if (lessons.countTutorLessonsOn(tutorId, day) >= CenterRules.MAX_LESSONS_PER_TUTOR_PER_DAY) {
            breaks.add(CenterRules.RULE_MAX_PER_DAY);
        }
        return breaks;
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
