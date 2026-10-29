package vn.brightpath.scheduling.schedule;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A day's schedule as the single source of truth, replacing the WhatsApp message chain: either one
 * tutor's day (what the tutor checks) or the whole centre's (what the owner and Mai check).
 *
 * @param tutorId          the tutor this day belongs to, or null for the whole centre
 * @param lockedAt         16:00 the day before; changes from then on are flagged afterLock
 * @param locked           whether the lock moment has passed (simulated now)
 * @param revision         number of changes recorded for this day; quote it in any message
 *                         ("schedule rev 3") so a tutor can tell which message is current
 * @param changedAfterLock lessons with at least one change after the lock; empty means
 *                         the schedule the tutor received at 16:00 is still exactly right
 * @param lessons          every lesson of the day, including cancelled and moved ones
 */
public record DaySchedule(
        String tutorId,
        LocalDate date,
        OffsetDateTime lockedAt,
        boolean locked,
        int revision,
        List<Long> changedAfterLock,
        List<Entry> lessons) {

    /**
     * @param replacedByLessonId set when status is 'rescheduled': where the lesson went
     * @param legacyConflict     imported row that overlapped another one in the spreadsheet
     */
    public record Entry(
            long lessonId,
            String externalRef,
            String studentName,
            String tutorId,
            String tutorName,
            String roomId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String status,
            Boolean lateCancel,
            Long replacesLessonId,
            Long replacedByLessonId,
            OffsetDateTime replacedByStartsAt,
            boolean legacyConflict,
            List<Change> changes) {
    }

    public record Change(String change, OffsetDateTime changedAt, boolean afterLock, String reason) {
    }
}
