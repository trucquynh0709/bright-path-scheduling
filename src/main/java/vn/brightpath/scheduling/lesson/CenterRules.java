package vn.brightpath.scheduling.lesson;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

/** The center's business rules that live in code rather than in the database. */
public final class CenterRules {

    public static final ZoneId CENTER_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final DayOfWeek CLOSED_DAY = DayOfWeek.MONDAY;
    public static final int MAX_LESSONS_PER_TUTOR_PER_DAY = 6;
    public static final Set<Integer> ALLOWED_DURATIONS_MIN = Set.of(60, 90);
    /** Cancelling with more notice than this is free; less notice is charged in full. */
    public static final Duration FREE_CANCELLATION_NOTICE = Duration.ofHours(4);
    /** Tomorrow's schedule is locked (sent to tutors) at this time today. */
    public static final LocalTime SCHEDULE_LOCK_TIME = LocalTime.of(16, 0);
    /**
     * The brief only says lessons run "mid-morning to mid-evening". These are the earliest start
     * and latest end the centre actually ran in the export (09:00, and L027 ending 21:30); an
     * assumption to confirm with the owner (DECISIONS.md, Q5).
     */
    public static final LocalTime OPENS_AT = LocalTime.of(9, 0);
    public static final LocalTime CLOSES_AT = LocalTime.of(21, 30);

    // Rule names, shared with the DB constraint names so errors and import issues read the same.
    public static final String RULE_CLOSED_ON_MONDAY = "closed_on_monday";
    public static final String RULE_MAX_PER_DAY = "max_6_lessons_per_tutor_per_day";
    public static final String RULE_OPENING_HOURS = "outside_opening_hours";

    private CenterRules() {
    }

    /** True if a lesson starting at {@code start} (centre time) fits entirely inside opening hours. */
    public static boolean isWithinOpeningHours(LocalTime start, int durationMin) {
        LocalTime end = start.plusMinutes(durationMin);
        // end.isAfter(start) also rejects a lesson that would run past midnight
        return !start.isBefore(OPENS_AT) && end.isAfter(start) && !end.isAfter(CLOSES_AT);
    }

    /** True if a cancellation at {@code cancelledAt} is charged (less than 4h before start). */
    public static boolean isLateCancel(Instant lessonStart, Instant cancelledAt) {
        return Duration.between(cancelledAt, lessonStart).compareTo(FREE_CANCELLATION_NOTICE) < 0;
    }

    /** 16:00 center time on the day before the lesson. */
    public static Instant lockMomentFor(Instant lessonStart) {
        return lessonStart.atZone(CENTER_ZONE).toLocalDate().minusDays(1)
                .atTime(SCHEDULE_LOCK_TIME).atZone(CENTER_ZONE).toInstant();
    }

    /** True if a change made at {@code changedAt} comes after the tutor received the locked schedule. */
    public static boolean isAfterLock(Instant lessonStart, Instant changedAt) {
        return !changedAt.isBefore(lockMomentFor(lessonStart));
    }
}
