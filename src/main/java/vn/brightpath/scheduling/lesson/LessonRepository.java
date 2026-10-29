package vn.brightpath.scheduling.lesson;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LessonRepository {

    /** Must match the WHERE clause of the exclusion constraints (V2__lesson_changes.sql). */
    private static final String HOLDS_SLOT = "status IN ('booked', 'no_show') AND NOT legacy_conflict";

    private final JdbcTemplate jdbc;

    public LessonRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Same checks as the DB exclusion constraints, but returns every blocking lesson so the
     * caller can explain the problem. The constraints remain the real guarantee.
     */
    public List<Conflict> findConflicts(long studentId, String tutorId, String roomId, Instant start, Instant end) {
        String overlaps = HOLDS_SLOT + " AND tstzrange(starts_at, ends_at) && tstzrange(?, ?)";
        String sql = """
                SELECT 'no_student_double_booking' AS rule, id, external_ref FROM lesson WHERE student_id = ? AND %1$s
                UNION ALL
                SELECT 'no_tutor_double_booking', id, external_ref FROM lesson WHERE tutor_id = ? AND %1$s
                UNION ALL
                SELECT 'no_room_double_booking', id, external_ref FROM lesson WHERE room_id = ? AND %1$s
                ORDER BY 1, 2
                """.formatted(overlaps);
        Timestamp s = Timestamp.from(start);
        Timestamp e = Timestamp.from(end);
        return jdbc.query(sql,
                (rs, i) -> new Conflict(rs.getString("rule"), rs.getLong("id"), rs.getString("external_ref")),
                studentId, s, e, tutorId, s, e, roomId, s, e);
    }

    /** Lessons holding a slot for this tutor on the given local (center time) day. */
    public int countTutorLessonsOn(String tutorId, LocalDate day) {
        Instant from = day.atStartOfDay(CenterRules.CENTER_ZONE).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(CenterRules.CENTER_ZONE).toInstant();
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM lesson WHERE tutor_id = ? AND starts_at >= ? AND starts_at < ? AND " + HOLDS_SLOT,
                Integer.class, tutorId, Timestamp.from(from), Timestamp.from(to));
        return n == null ? 0 : n;
    }

    public LessonView findById(long id) {
        return jdbc.queryForObject("""
                SELECT id, external_ref, student_id, tutor_id, room_id, starts_at, ends_at, status,
                       late_cancel, replaces_lesson_id, override_reason
                FROM lesson WHERE id = ?
                """, (rs, i) -> new LessonView(
                rs.getLong("id"), rs.getString("external_ref"), rs.getLong("student_id"),
                rs.getString("tutor_id"), rs.getString("room_id"),
                rs.getTimestamp("starts_at").toInstant().atZone(CenterRules.CENTER_ZONE).toOffsetDateTime(),
                rs.getTimestamp("ends_at").toInstant().atZone(CenterRules.CENTER_ZONE).toOffsetDateTime(),
                rs.getString("status"), rs.getObject("late_cancel", Boolean.class),
                rs.getObject("replaces_lesson_id", Long.class), rs.getString("override_reason")), id);
    }

    /** Appends to the lesson's history; after_lock is derived from the lesson's start time. */
    public void recordChange(long lessonId, String change, Instant lessonStart, Instant changedAt, String reason) {
        jdbc.update("""
                INSERT INTO lesson_change (lesson_id, change, changed_at, after_lock, reason)
                VALUES (?, ?, ?, ?, ?)
                """, lessonId, change, Timestamp.from(changedAt),
                CenterRules.isAfterLock(lessonStart, changedAt), reason);
    }

    public long countAll() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM lesson", Long.class);
        return n == null ? 0 : n;
    }

    /** Returns the id of the student with this name, creating the student if needed. */
    public long findOrCreateStudent(String fullName) {
        jdbc.update("INSERT INTO student (full_name) VALUES (?) ON CONFLICT (full_name) DO NOTHING", fullName);
        return jdbc.queryForObject("SELECT id FROM student WHERE full_name = ?", Long.class, fullName);
    }
}
