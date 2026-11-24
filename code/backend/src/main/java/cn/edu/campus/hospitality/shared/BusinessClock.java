package cn.edu.campus.hospitality.shared;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/** Provides a consistent business date and time for reservation rules. */
@Component
public class BusinessClock {
  private final Clock clock;

  /** Creates the business clock from the configured time source. */
  public BusinessClock(Clock clock) {
    this.clock = clock;
  }

  /** Returns the current time in the business time zone. */
  public LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  /** Returns the current business date. */
  public LocalDate today() {
    return LocalDate.now(clock);
  }

  /** Returns the booking policy cutoff on the day before arrival. */
  public LocalDateTime cutoff(LocalDate start) {
    return start.minusDays(1).atTime(18, 0);
  }

  @Configuration
  static class Config {
    @Bean
    Clock clock(@Value("${app.zone:Asia/Shanghai}") String zone) {
      return Clock.system(ZoneId.of(zone));
    }
  }
}
