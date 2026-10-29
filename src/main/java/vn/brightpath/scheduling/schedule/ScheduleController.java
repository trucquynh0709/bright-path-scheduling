package vn.brightpath.scheduling.schedule;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ScheduleController {

    private final DayScheduleQuery query;

    public ScheduleController(DayScheduleQuery query) {
        this.query = query;
    }

    @GetMapping("/tutors/{tutorId}/schedule")
    public DaySchedule schedule(@PathVariable String tutorId,
                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return query.forDay(tutorId, date);
    }
}
