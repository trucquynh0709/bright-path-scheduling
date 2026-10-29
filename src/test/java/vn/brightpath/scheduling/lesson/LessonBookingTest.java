package vn.brightpath.scheduling.lesson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import vn.brightpath.scheduling.TestcontainersConfiguration;

/**
 * Runs against the real seed (data/) with "now" = Thu 2026-03-05 14:00.
 * Each test rolls back, so they all start from the freshly imported spreadsheet.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class LessonBookingTest {

    // Student ids follow first appearance in data/seed.csv
    private static final long LE_MINH_CHAU = 1;
    private static final long DO_VAN_KIEN = 4;
    private static final long VU_HA_MY = 5;
    private static final long BUI_AN_NHIEN = 6;

    @Autowired
    LessonService service;
    @Autowired
    LessonRepository lessons;
    @Autowired
    JdbcTemplate jdbc;

    private static BookLessonRequest request(long studentId, String tutor, String room, String startsAt, int minutes) {
        return new BookLessonRequest(studentId, tutor, room, LocalDateTime.parse(startsAt), minutes);
    }

    @Test
    void seedImportKeepsEveryRowAndFlagsTheOverlappingOnes() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lesson", Integer.class)).isEqualTo(34);
        assertThat(jdbc.queryForList("SELECT external_ref FROM lesson WHERE legacy_conflict ORDER BY 1", String.class))
                .containsExactly("L008", "L010", "L034");
        assertThat(jdbc.queryForList("SELECT external_ref || ' ' || rule FROM import_issue ORDER BY id", String.class))
                .containsExactly(
                        "L008 no_student_double_booking",
                        "L010 no_room_double_booking",
                        "L010 no_tutor_double_booking",
                        "L027 max_6_lessons_per_tutor_per_day",
                        "L032 closed_on_monday",
                        "L034 no_tutor_double_booking");
    }

    @Test
    void seedCancellationsGetTheFourHourRuleAndAHistoryEntry() {
        // L005 cancelled 08:15 for 14:00 (5h45 notice) -> free; L017 cancelled 14:40 for 16:00 -> charged.
        // Both were cancelled on the day itself, i.e. after the previous day's 16:00 lock.
        assertThat(jdbc.queryForList("""
                SELECT l.external_ref || ' late=' || l.late_cancel || ' afterLock=' || c.after_lock
                FROM lesson l JOIN lesson_change c ON c.lesson_id = l.id ORDER BY 1
                """, String.class))
                .containsExactly("L005 late=false afterLock=true", "L017 late=true afterLock=true");
    }

    @Test
    void studentCannotBeInTwoPlacesAtOnce() {
        // Le Minh Chau already has L019: Fri 09:00-10:00 with T2 in R2
        assertThatThrownBy(() -> service.book(request(LE_MINH_CHAU, "T3", "R4", "2026-03-06T09:30", 60)))
                .isInstanceOfSatisfying(RuleViolationException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.rule()).isEqualTo("no_student_double_booking");
                    assertThat(e.details()).isEqualTo(List.of(new Conflict("no_student_double_booking", 19, "L019")));
                });
    }

    @Test
    void examPairInSameRoomIsRejected() {
        // L030 is T1 in R1 on Sat 15:00; a second student in the same slot is the "exam pair"
        assertThatThrownBy(() -> service.book(request(DO_VAN_KIEN, "T1", "R1", "2026-03-07T15:00", 60)))
                .isInstanceOfSatisfying(RuleViolationException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void backToBackLessonsAreAllowed() {
        // L019 ends at 10:00; starting exactly at 10:00 is not an overlap
        LessonView lesson = service.book(request(LE_MINH_CHAU, "T2", "R2", "2026-03-06T10:00", 60));
        assertThat(lesson.status()).isEqualTo("booked");
    }

    @Test
    void seventhLessonForATutorIsRejected() {
        // T1 already has 7 lessons on Fri 2026-03-06 in the export; the owner wants the limit enforced
        assertThatThrownBy(() -> service.book(request(DO_VAN_KIEN, "T1", "R5", "2026-03-06T10:00", 60)))
                .isInstanceOfSatisfying(RuleViolationException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(e.rule()).isEqualTo(CenterRules.RULE_MAX_PER_DAY);
                });
    }

    @Test
    void tutorUnderTheLimitCanStillBeBooked() {
        // T3 has one lesson (L020) on Fri 03-06
        assertThat(service.book(request(DO_VAN_KIEN, "T3", "R5", "2026-03-06T18:00", 60)).status()).isEqualTo("booked");
    }

    @Test
    void mondayIsRejected() {
        assertThatThrownBy(() -> service.book(request(DO_VAN_KIEN, "T2", "R4", "2026-03-09T09:00", 60)))
                .isInstanceOfSatisfying(RuleViolationException.class,
                        e -> assertThat(e.rule()).isEqualTo(CenterRules.RULE_CLOSED_ON_MONDAY));
    }

    @Test
    void lessonsMustFitInsideOpeningHours() {
        // 09:00-21:30, taken from the export; 08:00 starts too early, 21:00 for 60 min ends too late
        for (String startsAt : new String[] {"2026-03-07T08:00", "2026-03-07T21:00"}) {
            assertThatThrownBy(() -> service.book(request(DO_VAN_KIEN, "T2", "R4", startsAt, 60)))
                    .isInstanceOfSatisfying(RuleViolationException.class,
                            e -> assertThat(e.rule()).isEqualTo(CenterRules.RULE_OPENING_HOURS));
        }
        // The last lesson the centre really runs (20:30-21:30, like L027) is still allowed
        assertThat(service.book(request(DO_VAN_KIEN, "T2", "R4", "2026-03-07T20:30", 60)).status()).isEqualTo("booked");
    }

    @Test
    void cannotBookBeforeSimulatedNow() {
        assertThatThrownBy(() -> service.book(request(DO_VAN_KIEN, "T2", "R4", "2026-03-05T13:00", 60)))
                .isInstanceOfSatisfying(RuleViolationException.class,
                        e -> assertThat(e.rule()).isEqualTo("booking_in_the_past"));
    }

    @Test
    void databaseRejectsDoubleBookingEvenWhenCodeIsBypassed() {
        // Simulates a bug or a manual SQL edit skipping LessonService: the constraint still holds.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO lesson (student_id, tutor_id, room_id, starts_at, ends_at)
                VALUES (?, 'T3', 'R6', '2026-03-06 09:30+07', '2026-03-06 10:30+07')
                """, LE_MINH_CHAU))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("no_student_double_booking");
    }

    @Test
    void cancelledLessonDoesNotHoldItsSlot() {
        // L005 (Vu Ha My, T2, R2, Tue 03-03 14:00-15:00) was cancelled, so nothing blocks that slot
        Instant start = LocalDateTime.parse("2026-03-03T14:00").atZone(CenterRules.CENTER_ZONE).toInstant();
        List<Conflict> conflicts = lessons.findConflicts(VU_HA_MY, "T2", "R2", start, start.plusSeconds(3600));
        assertThat(conflicts).isEmpty();
    }

    @Test
    void noShowStillHoldsItsSlot() {
        // L015 (Tran Bao Long, T2, R2, Thu 03-05 13:00) was a no-show: room and tutor stay taken
        Instant start = LocalDateTime.parse("2026-03-05T13:00").atZone(CenterRules.CENTER_ZONE).toInstant();
        List<Conflict> conflicts = lessons.findConflicts(BUI_AN_NHIEN, "T2", "R2", start, start.plusSeconds(3600));
        assertThat(conflicts).extracting(Conflict::externalRef).containsOnly("L015");
    }
}
