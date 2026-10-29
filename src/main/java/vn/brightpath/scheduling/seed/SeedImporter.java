package vn.brightpath.scheduling.seed;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import vn.brightpath.scheduling.lesson.CenterRules;
import vn.brightpath.scheduling.lesson.Conflict;
import vn.brightpath.scheduling.lesson.LessonRepository;

/**
 * Loads the spreadsheet export (data/tutors.csv, data/seed.csv) into an empty database.
 *
 * The export is what the center really ran, so it breaks the rules. Nothing is dropped:
 * <ul>
 *   <li>a row overlapping an earlier row (same student, tutor or room) is stored with
 *       legacy_conflict = true, so it stays visible as history but does not hold a slot;</li>
 *   <li>Monday lessons and tutors above 6 lessons/day are stored as-is;</li>
 *   <li>every violation is written to import_issue and printed as a report.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true", matchIfMissing = true)
public class SeedImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedImporter.class);

    private final JdbcTemplate jdbc;
    private final LessonRepository lessons;
    private final Path seedDir;

    public SeedImporter(JdbcTemplate jdbc, LessonRepository lessons, @Value("${app.seed.dir:data}") Path seedDir) {
        this.jdbc = jdbc;
        this.lessons = lessons;
        this.seedDir = seedDir;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        if (lessons.countAll() > 0) {
            log.info("Seed skipped: lessons already present");
            return;
        }
        importTutors(seedDir.resolve("tutors.csv"));
        List<String> report = importLessons(seedDir.resolve("seed.csv"));
        log.info("Seed import finished with {} issue(s):{}", report.size(),
                report.isEmpty() ? "" : "\n  " + String.join("\n  ", report));
    }

    private void importTutors(Path file) throws IOException {
        for (Map<String, String> row : Csv.read(file)) {
            jdbc.update("INSERT INTO tutor (id, name, subject, phone) VALUES (?, ?, ?, ?)",
                    row.get("tutor_id"), row.get("tutor_name"), row.get("subject"), row.get("phone"));
        }
    }

    private List<String> importLessons(Path file) throws IOException {
        List<String> report = new ArrayList<>();
        int imported = 0;
        for (Map<String, String> row : Csv.read(file)) {
            String ref = row.get("lesson_id");
            LocalDate day = LocalDate.parse(row.get("date"));
            Instant start = day.atTime(LocalTime.parse(row.get("start_time"))).atZone(CenterRules.CENTER_ZONE).toInstant();
            Instant end = start.plusSeconds(60L * Integer.parseInt(row.get("duration_min")));
            String status = row.get("status");
            String tutorId = row.get("tutor_id");
            String roomId = row.get("room");
            long studentId = lessons.findOrCreateStudent(row.get("student"));
            String cancelledAt = row.get("cancelled_at");

            List<String> issues = new ArrayList<>();
            boolean legacyConflict = false;
            // A cancelled lesson holds no slot, so only slot-holding lessons are checked.
            if ("booked".equals(status) || "no_show".equals(status)) {
                if (day.getDayOfWeek() == CenterRules.CLOSED_DAY) {
                    issues.add(CenterRules.RULE_CLOSED_ON_MONDAY + ": lesson on " + day);
                }
                int sameDay = lessons.countTutorLessonsOn(tutorId, day);
                if (sameDay >= CenterRules.MAX_LESSONS_PER_TUTOR_PER_DAY) {
                    issues.add(CenterRules.RULE_MAX_PER_DAY + ": " + tutorId + " already has " + sameDay + " on " + day);
                }
                for (Conflict c : lessons.findConflicts(studentId, tutorId, roomId, start, end)) {
                    legacyConflict = true;
                    issues.add(c.rule() + ": overlaps " + c.externalRef());
                }
            }

            Instant cancelled = cancelledAt.isEmpty() ? null : OffsetDateTime.parse(cancelledAt).toInstant();
            String note = row.get("note").isEmpty() ? null : row.get("note");
            long lessonId = jdbc.queryForObject("""
                    INSERT INTO lesson (external_ref, student_id, tutor_id, room_id, starts_at, ends_at,
                                        status, cancelled_at, late_cancel, note, legacy_conflict)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class,
                    ref, studentId, tutorId, roomId, Timestamp.from(start), Timestamp.from(end), status,
                    cancelled == null ? null : Timestamp.from(cancelled),
                    cancelled == null ? null : CenterRules.isLateCancel(start, cancelled),
                    note, legacyConflict);
            // The export only timestamps cancellations, so those are the only history we can rebuild.
            if (cancelled != null) {
                lessons.recordChange(lessonId, "cancelled", start, cancelled, note);
            }
            imported++;

            for (String issue : issues) {
                int colon = issue.indexOf(':');
                jdbc.update("INSERT INTO import_issue (external_ref, rule, detail) VALUES (?, ?, ?)",
                        ref, issue.substring(0, colon), issue.substring(colon + 2));
                report.add(ref + " " + issue);
            }
        }
        log.info("Imported {} lessons from {}", imported, file);
        return report;
    }
}
