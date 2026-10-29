package vn.brightpath.scheduling.lesson;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * A booking that breaks a center rule.
 *
 * @param status  409 for a clash with another lesson, 422 for other rule breaks, 404 for unknown ids
 * @param rule    machine-readable rule name (same names as the DB constraints / CenterRules)
 * @param details e.g. the lessons it clashes with
 */
public class BookingRejectedException extends RuntimeException {

    private final HttpStatus status;
    private final String rule;
    private final List<?> details;

    public BookingRejectedException(HttpStatus status, String rule, String message, List<?> details) {
        super(message);
        this.status = status;
        this.rule = rule;
        this.details = details;
    }

    public BookingRejectedException(HttpStatus status, String rule, String message) {
        this(status, rule, message, List.of());
    }

    public HttpStatus status() {
        return status;
    }

    public String rule() {
        return rule;
    }

    public List<?> details() {
        return details;
    }
}
