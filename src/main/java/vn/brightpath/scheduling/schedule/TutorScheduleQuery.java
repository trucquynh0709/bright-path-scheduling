package vn.brightpath.scheduling.schedule;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.brightpath.scheduling.lesson.CenterRules;
import vn.brightpath.scheduling.lesson.RuleViolationException;

/** Read-only view of a tutor's day built from lesson + lesson_change. */
@Component
public class TutorScheduleQuery {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public TutorScheduleQuery(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TutorSchedule forDay(String tutorId, LocalDate date) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM tutor WHERE id = ?)", Boolean.class, tutorId);
        if (!Boolean.TRUE.equals(exists)) {
            throw new RuleViolationException(HttpStatus.NOT_FOUND, "tutor_not_found", "No tutor with id " + tutorId);
        }
        Instant dayStart = date.atStartOfDay(CenterRules.CENTER_ZONE).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(CenterRules.CENTER_ZONE).toInstant();
        Instant lockedAt = CenterRules.lockMomentFor(dayStart);

        Map<Long, List<TutorSchedule.Change>> changes = new HashMap<>();
        jdbc.query("""
                SELECT c.lesson_id, c.change, c.changed_at, c.after_lock, c.reason
                FROM lesson_change c JOIN lesson l ON l.id = c.lesson_id
                WHERE l.tutor_id = ? AND l.starts_at >= ? AND l.starts_at < ?
                ORDER BY c.id
                """, rs -> {
            changes.computeIfAbsent(rs.getLong("lesson_id"), k -> new ArrayList<>()).add(new TutorSchedule.Change(
                    rs.getString("change"), toCenterTime(rs, "changed_at"), rs.getBoolean("after_lock"),
                    rs.getString("reason")));
        }, tutorId, Timestamp.from(dayStart), Timestamp.from(dayEnd));

        List<TutorSchedule.Entry> lessons = jdbc.query("""
                SELECT l.id, l.external_ref, s.full_name, l.room_id, l.starts_at, l.ends_at, l.status,
                       l.late_cancel, l.replaces_lesson_id, l.legacy_conflict,
                       next.id AS replaced_by_id, next.starts_at AS replaced_by_starts_at
                FROM lesson l
                JOIN student s ON s.id = l.student_id
                LEFT JOIN lesson next ON next.replaces_lesson_id = l.id
                WHERE l.tutor_id = ? AND l.starts_at >= ? AND l.starts_at < ?
                ORDER BY l.starts_at, l.id
                """, (rs, i) -> new TutorSchedule.Entry(
                rs.getLong("id"), rs.getString("external_ref"), rs.getString("full_name"), rs.getString("room_id"),
                toCenterTime(rs, "starts_at"), toCenterTime(rs, "ends_at"), rs.getString("status"),
                rs.getObject("late_cancel", Boolean.class), rs.getObject("replaces_lesson_id", Long.class),
                rs.getObject("replaced_by_id", Long.class), toCenterTime(rs, "replaced_by_starts_at"),
                rs.getBoolean("legacy_conflict"), changes.getOrDefault(rs.getLong("id"), List.of())),
                tutorId, Timestamp.from(dayStart), Timestamp.from(dayEnd));

        List<Long> changedAfterLock = lessons.stream()
                .filter(l -> l.changes().stream().anyMatch(TutorSchedule.Change::afterLock))
                .map(TutorSchedule.Entry::lessonId)
                .toList();
        int revision = changes.values().stream().mapToInt(List::size).sum();

        return new TutorSchedule(tutorId, date, lockedAt.atZone(CenterRules.CENTER_ZONE).toOffsetDateTime(),
                !clock.instant().isBefore(lockedAt), revision, changedAfterLock, lessons);
    }

    private static OffsetDateTime toCenterTime(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant().atZone(CenterRules.CENTER_ZONE).toOffsetDateTime();
    }
}
