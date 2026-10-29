package vn.brightpath.scheduling.lesson;

/**
 * An existing lesson that blocks a new one.
 *
 * @param rule        name of the DB exclusion constraint that would be violated
 * @param lessonId    the blocking lesson
 * @param externalRef its spreadsheet id (e.g. "L007"), or null if created in this tool
 */
public record Conflict(String rule, long lessonId, String externalRef) {
}
