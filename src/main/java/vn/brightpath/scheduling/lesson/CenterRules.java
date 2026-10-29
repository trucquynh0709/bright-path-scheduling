package vn.brightpath.scheduling.lesson;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.Set;

/** The center's business rules that live in code rather than in the database. */
public final class CenterRules {

    public static final ZoneId CENTER_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final DayOfWeek CLOSED_DAY = DayOfWeek.MONDAY;
    public static final int MAX_LESSONS_PER_TUTOR_PER_DAY = 6;
    public static final Set<Integer> ALLOWED_DURATIONS_MIN = Set.of(60, 90);

    // Rule names, shared with the DB constraint names so errors and import issues read the same.
    public static final String RULE_CLOSED_ON_MONDAY = "closed_on_monday";
    public static final String RULE_MAX_PER_DAY = "max_6_lessons_per_tutor_per_day";

    private CenterRules() {
    }
}
