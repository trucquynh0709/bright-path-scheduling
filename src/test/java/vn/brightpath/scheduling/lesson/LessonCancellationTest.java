package vn.brightpath.scheduling.lesson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import vn.brightpath.scheduling.TestcontainersConfiguration;

/** "Now" is Thu 2026-03-05 14:00; each test rolls back to the freshly imported seed. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class LessonCancellationTest {

    @Autowired
    LessonService service;
    @Autowired
    JdbcTemplate jdbc;

    private long idOf(String externalRef) {
        return jdbc.queryForObject("SELECT id FROM lesson WHERE external_ref = ?", Long.class, externalRef);
    }

    private Map<String, Object> lastChange(long lessonId) {
        return jdbc.queryForMap(
                "SELECT change, after_lock, reason FROM lesson_change WHERE lesson_id = ? ORDER BY id DESC LIMIT 1",
                lessonId);
    }

    @Test
    void cancelWithMoreThanFourHoursNoticeIsFreeAndBeforeLock() {
        // L028: Sat 03-07 09:00. Its lock is Fri 16:00, still in the future.
        LessonView lesson = service.cancel(idOf("L028"), "family travelling");
        assertThat(lesson.status()).isEqualTo("cancelled");
        assertThat(lesson.lateCancel()).isFalse();
        assertThat(lastChange(lesson.id()))
                .containsEntry("change", "cancelled").containsEntry("after_lock", false)
                .containsEntry("reason", "family travelling");
    }

    @Test
    void lateCancelIsChargedFlaggedAfterLockAndStillFreesTheRoom() {
        // L016: Thu 03-05 14:30 (T1, R1), 30 min from now, so charged and after Wed's 16:00 lock
        long id = idOf("L016");
        LessonView lesson = service.cancel(id, "family cancelled");
        assertThat(lesson.lateCancel()).isTrue();
        assertThat(lastChange(id)).containsEntry("after_lock", true);

        // The room and tutor are released: another student can take the slot
        LessonView replacement = service.book(new BookLessonRequest(
                4L, "T1", "R1", LocalDateTime.parse("2026-03-05T14:30"), 60, null));
        assertThat(replacement.status()).isEqualTo("booked");
    }

    @Test
    void cancellingTwiceIsRejected() {
        long id = idOf("L028");
        service.cancel(id, null);
        assertThatThrownBy(() -> service.cancel(id, null))
                .isInstanceOfSatisfying(RuleViolationException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.rule()).isEqualTo("lesson_not_booked");
                });
    }

    @Test
    void startedLessonCannotBeCancelledOnlyMarkedNoShow() {
        // L014: Thu 03-05 10:30, already started
        long id = idOf("L014");
        assertThatThrownBy(() -> service.cancel(id, null))
                .isInstanceOfSatisfying(RuleViolationException.class,
                        e -> assertThat(e.rule()).isEqualTo("lesson_already_started"));

        LessonView noShow = service.markNoShow(id);
        assertThat(noShow.status()).isEqualTo("no_show");
        assertThat(lastChange(id)).containsEntry("change", "no_show");
    }

    @Test
    void historyCannotBeRewritten() {
        assertThatThrownBy(() -> jdbc.update("DELETE FROM lesson_change"))
                .hasMessageContaining("append-only");
    }
}
