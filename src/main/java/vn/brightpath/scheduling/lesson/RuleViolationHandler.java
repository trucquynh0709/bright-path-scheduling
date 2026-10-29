package vn.brightpath.scheduling.lesson;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns a RuleViolationException from any endpoint into an RFC 9457 problem response. */
@RestControllerAdvice
public class RuleViolationHandler {

    @ExceptionHandler(RuleViolationException.class)
    public ProblemDetail rejected(RuleViolationException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setTitle("Request rejected by center rules");
        problem.setProperty("rule", e.rule());
        if (!e.details().isEmpty()) {
            problem.setProperty("details", e.details());
        }
        return problem;
    }
}
