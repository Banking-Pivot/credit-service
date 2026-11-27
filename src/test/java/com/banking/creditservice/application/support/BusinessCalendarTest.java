package com.banking.creditservice.application.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Business dates of the clock, without Spring. */
class BusinessCalendarTest {

  private static final ZoneId LIMA = ZoneId.of("America/Lima");

  @Test
  void todayIsTheDateInTheBusinessZone() {
    // 23:30 of 15 November in Lima is already 16 November in UTC.
    BusinessCalendar lateEvening = calendarAt("2026-11-16T04:30:00Z");
    BusinessCalendar afterMidnight = calendarAt("2026-11-16T05:00:00Z");

    assertThat(lateEvening.today()).isEqualTo(LocalDate.of(2026, 11, 15));
    assertThat(afterMidnight.today()).isEqualTo(LocalDate.of(2026, 11, 16));
    assertThat(afterMidnight.zone()).isEqualTo(LIMA);
  }

  @Test
  void todayFollowsAnOffsetClock() {
    Clock shifted = Clock.offset(clockAt("2026-11-16T15:00:00Z"), java.time.Duration.ofDays(40));

    assertThat(new BusinessCalendar(shifted, LIMA).today()).isEqualTo(LocalDate.of(2026, 12, 26));
  }

  private static BusinessCalendar calendarAt(String instant) {
    return new BusinessCalendar(clockAt(instant), LIMA);
  }

  private static Clock clockAt(String instant) {
    return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
  }
}
