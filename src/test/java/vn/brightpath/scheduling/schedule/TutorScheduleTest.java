package vn.brightpath.scheduling.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import vn.brightpath.scheduling.TestcontainersConfiguration;
import vn.brightpath.scheduling.lesson.LessonService;
import vn.brightpath.scheduling.lesson.LessonView;
import vn.brightpath.scheduling.lesson.RescheduleLessonRequest;

/** "Now" is Thu 2026-03-05 14:00; each test rolls back to the freshly imported seed. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class TutorScheduleTest {

    @Autowired
    TutorScheduleQuery query;
    @Autowired
    LessonService service;
    @Autowired
    JdbcTemplate jdbc;

    private long idOf(String externalRef) {
        return jdbc.queryForObject("SELECT id FROM lesson WHERE external_ref = ?", Long.class, externalRef);
    }

    @Test
    void lateCancelTodayStaysOnTheScheduleAndIsFlaggedAsAChange() {
        // T1 on Thu 03-05: L013 09:00 and L016 14:30. Thursday's lock was Wed 16:00, already passed.
        long l016 = idOf("L016");
        service.cancel(l016, "family cancelled");

        TutorSchedule day = query.forDay("T1", LocalDate.parse("2026-03-05"));
        assertThat(day.locked()).isTrue();
        assertThat(day.revision()).isEqualTo(1);
        assertThat(day.changedAfterLock()).containsExactly(l016);
        assertThat(day.lessons()).extracting(TutorSchedule.Entry::externalRef, TutorSchedule.Entry::status)
                .containsExactly(
                        tuple("L013", "booked"),
                        tuple("L016", "cancelled"));
    }

    @Test
    void changesBeforeTheLockAreHistoryNotAlerts() {
        // Fri 03-06 locks at Thu 16:00, two hours after "now"
        service.cancel(idOf("L018"), "family cancelled");
        TutorSchedule day = query.forDay("T1", LocalDate.parse("2026-03-06"));
        assertThat(day.locked()).isFalse();
        assertThat(day.revision()).isEqualTo(1);
        assertThat(day.changedAfterLock()).isEmpty();
    }

    @Test
    void movedLessonShowsWhereItWent() {
        long original = idOf("L028");
        LessonView moved = service.reschedule(original, new RescheduleLessonRequest(
                LocalDateTime.parse("2026-03-07T13:00"), null, null, null, "exam moved", null));

        TutorSchedule day = query.forDay("T3", LocalDate.parse("2026-03-07"));
        TutorSchedule.Entry old = day.lessons().stream().filter(l -> l.lessonId() == original).findFirst().orElseThrow();
        assertThat(old.status()).isEqualTo("rescheduled");
        assertThat(old.replacedByLessonId()).isEqualTo(moved.id());
        assertThat(old.replacedByStartsAt().toLocalTime()).hasToString("13:00");
        assertThat(day.revision()).isEqualTo(2); // 'rescheduled' on the old lesson + 'booked' on the new one
    }

    @Test
    void importedSeedCancellationIsShownWithItsHistory() {
        // L017 (T3, Thu 16:00) was cancelled at 14:40 the same day in the spreadsheet: charged, after lock
        TutorSchedule day = query.forDay("T3", LocalDate.parse("2026-03-05"));
        TutorSchedule.Entry l017 = day.lessons().stream()
                .filter(l -> "L017".equals(l.externalRef())).findFirst().orElseThrow();
        assertThat(l017.lateCancel()).isTrue();
        assertThat(l017.changes()).singleElement().satisfies(c -> {
            assertThat(c.afterLock()).isTrue();
            assertThat(c.reason()).isEqualTo("tutor sick");
        });
    }
}
