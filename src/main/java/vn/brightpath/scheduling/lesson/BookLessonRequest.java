package vn.brightpath.scheduling.lesson;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @param startsAt local center time (Asia/Ho_Chi_Minh), e.g. "2026-03-06T09:00"
 */
public record BookLessonRequest(
        @NotNull Long studentId,
        @NotBlank String tutorId,
        @NotBlank String roomId,
        @NotNull LocalDateTime startsAt,
        @NotNull Integer durationMin) {
}
