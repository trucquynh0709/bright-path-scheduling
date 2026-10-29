package vn.brightpath.scheduling.lesson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import vn.brightpath.scheduling.TestcontainersConfiguration;

/** "Now" is Thu 2026-03-05 14:00; each test rolls back to the freshly imported seed. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class LessonRescheduleTest {

    @Autowired
    LessonService service;
    @Autowired
    JdbcTemplate jdbc;

    private long idOf(String externalRef) {
        return jdbc.queryForObject("SELECT id FROM lesson WHERE external_ref = ?", Long.class, externalRef);
    }

    private String statusOf(long lessonId) {
        return jdbc.queryForObject("SELECT status FROM lesson WHERE id = ?", String.class, lessonId);
    }

    private static RescheduleLessonRequest moveTo(String startsAt, String roomId, String reason) {
        return new RescheduleLessonRequest(LocalDateTime.parse(startsAt), null, null, roomId, reason);
    }

    @Test
    void movedLessonIsANewRowAndTheOriginalStaysVisible() {
        // L028: Tran Bao Long, T3, R3, Sat 03-07 09:00, 90 min -> Sat 13:00
        long original = idOf("L028");
        LessonView moved = service.reschedule(original, moveTo("2026-03-07T13:00", null, "exam moved"));

        assertThat(moved.id()).isNotEqualTo(original);
        assertThat(moved.replacesLessonId()).isEqualTo(original);
        assertThat(moved.tutorId()).isEqualTo("T3");
        assertThat(moved.endsAt().toLocalTime()).hasToString("14:30");
        assertThat(statusOf(original)).isEqualTo("rescheduled");
        assertThat(jdbc.queryForList("""
                SELECT lesson_id || ' ' || change || ' ' || reason FROM lesson_change
                WHERE lesson_id IN (?, ?) ORDER BY id
                """, String.class, original, moved.id()))
                .containsExactly(
                        original + " rescheduled exam moved",
                        moved.id() + " booked moved from lesson " + original + ": exam moved");
    }

    @Test
    void movingOverlappingItsOwnOldSlotIsAllowed() {
        // Shift L028 (09:00-10:30) by 30 minutes: it overlaps only itself, which no longer holds the slot
        LessonView moved = service.reschedule(idOf("L028"), moveTo("2026-03-07T09:30", null, null));
        assertThat(moved.status()).isEqualTo("booked");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // let the service's own transaction roll back
    void rejectedMoveLeavesTheOriginalUntouched() {
        // Move L028 into R2 at Sat 11:00, where L029 (T2, R2, 11:00) already is
        long original = idOf("L028");
        assertThatThrownBy(() -> service.reschedule(original, moveTo("2026-03-07T11:00", "R2", null)))
                .isInstanceOfSatisfying(RuleViolationException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.rule()).isEqualTo("no_room_double_booking");
                });
        assertThat(statusOf(original)).isEqualTo("booked");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lesson_change WHERE lesson_id = ?", Integer.class, original))
                .isZero();
    }

    @Test
    void onlyTheLatestLessonInAChainCanBeMoved() {
        long original = idOf("L028");
        LessonView moved = service.reschedule(original, moveTo("2026-03-07T13:00", null, null));
        assertThatThrownBy(() -> service.reschedule(original, moveTo("2026-03-07T15:00", null, null)))
                .isInstanceOfSatisfying(RuleViolationException.class,
                        e -> assertThat(e.rule()).isEqualTo("lesson_not_booked"));
        assertThat(service.reschedule(moved.id(), moveTo("2026-03-07T15:00", null, null)).replacesLessonId())
                .isEqualTo(moved.id());
    }
}
