package vn.brightpath.scheduling.lesson;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/lessons")
public class LessonController {

    private final LessonService service;

    public LessonController(LessonService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<LessonView> book(@Valid @RequestBody BookLessonRequest req) {
        LessonView lesson = service.book(req);
        return ResponseEntity.created(URI.create("/lessons/" + lesson.id())).body(lesson);
    }

    @ExceptionHandler(BookingRejectedException.class)
    public ProblemDetail rejected(BookingRejectedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setTitle("Booking rejected");
        problem.setProperty("rule", e.rule());
        if (!e.details().isEmpty()) {
            problem.setProperty("details", e.details());
        }
        return problem;
    }
}
