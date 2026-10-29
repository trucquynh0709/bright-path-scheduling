package vn.brightpath.scheduling.lesson;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotNull;

/**
 * Where the lesson moves to. Tutor, room and duration default to the original lesson's.
 *
 * @param startsAt       new start, local center time
 * @param reason         why it moved, shown to the tutor
 * @param overrideReason as for booking: needed only if the new slot breaks a soft rule
 */
public record RescheduleLessonRequest(
        @NotNull LocalDateTime startsAt,
        Integer durationMin,
        String tutorId,
        String roomId,
        String reason,
        String overrideReason) {
}
