package vn.brightpath.scheduling.lesson;

import java.time.OffsetDateTime;

/** A lesson as returned by the API; times are in center time (+07:00). */
public record LessonView(
        long id,
        String externalRef,
        long studentId,
        String tutorId,
        String roomId,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        String status,
        Boolean lateCancel,
        Long replacesLessonId) {
}
