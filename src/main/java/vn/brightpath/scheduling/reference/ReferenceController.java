package vn.brightpath.scheduling.reference;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.brightpath.scheduling.lesson.CenterRules;

/** Lookup lists for the UI's dropdowns, plus the (possibly simulated) current time. */
@RestController
public class ReferenceController {

    /** phone is shown so Mai can call a tutor about a change made after the 16:00 cut-off. */
    public record Tutor(String id, String name, String subject, String phone) {
    }

    public record Student(long id, String fullName) {
    }

    public record Reference(OffsetDateTime now, List<Tutor> tutors, List<String> rooms, List<Student> students) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReferenceController(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/reference")
    public Reference reference() {
        return new Reference(
                clock.instant().atZone(CenterRules.CENTER_ZONE).toOffsetDateTime(),
                jdbc.query("SELECT id, name, subject, phone FROM tutor ORDER BY id",
                        (rs, i) -> new Tutor(rs.getString("id"), rs.getString("name"), rs.getString("subject"),
                                rs.getString("phone"))),
                jdbc.queryForList("SELECT id FROM room ORDER BY id", String.class),
                jdbc.query("SELECT id, full_name FROM student ORDER BY full_name",
                        (rs, i) -> new Student(rs.getLong("id"), rs.getString("full_name"))));
    }
}
