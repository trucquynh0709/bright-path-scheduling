package vn.brightpath.scheduling.lesson;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LessonRepository {

    /** A lesson holds its slot unless cancelled. Legacy conflicts never hold a slot. */
    private static final String HOLDS_SLOT = "status <> 'cancelled' AND NOT legacy_conflict";

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
