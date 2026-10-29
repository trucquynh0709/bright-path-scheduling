package vn.brightpath.scheduling.lesson;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
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

    /** POST, not DELETE: the lesson is not removed, it gains a cancelled state and a history entry. */
    @PostMapping("/{id}/cancel")
    public LessonView cancel(@PathVariable long id, @RequestBody(required = false) CancelLessonRequest req) {
        return service.cancel(id, req == null ? null : req.reason());
    }

    @PostMapping("/{id}/no-show")
    public LessonView noShow(@PathVariable long id) {
        return service.markNoShow(id);
    }
}
