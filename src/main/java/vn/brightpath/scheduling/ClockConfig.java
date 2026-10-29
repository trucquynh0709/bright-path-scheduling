package vn.brightpath.scheduling;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import vn.brightpath.scheduling.lesson.CenterRules;

/**
 * Every "now"-dependent rule (no booking in the past, 4h cancellation, 16:00 lock) reads this
 * Clock, never the system time directly. app.now pins it to a moment inside the seed week so
 * the demo and tests behave the same on any day; leave it empty to use the real clock.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(@Value("${app.now:}") String now) {
        if (now.isBlank()) {
            return Clock.system(CenterRules.CENTER_ZONE);
        }
        return Clock.fixed(OffsetDateTime.parse(now).toInstant(), CenterRules.CENTER_ZONE);
    }
}
