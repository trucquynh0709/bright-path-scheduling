package vn.brightpath.scheduling.lesson;

/** @param reason free text shown to the tutor, e.g. "family cancelled", "tutor sick" */
public record CancelLessonRequest(String reason) {
}
