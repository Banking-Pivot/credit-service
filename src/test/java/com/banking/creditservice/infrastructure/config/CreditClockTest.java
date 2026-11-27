package com.banking.creditservice.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** {@code credit.clock-offset} moves the clock every date of the service comes from. */
class CreditClockTest {

  private static final Duration TOLERANCE = Duration.ofSeconds(30);

  private final CreditBeanConfiguration configuration = new CreditBeanConfiguration();

  @Test
  void withoutOffsetTheClockIsTheSystemClockInUtc() {
    Clock clock = configuration.creditClock(properties(Duration.ZERO));

    assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    assertThat(Duration.between(Instant.now(), clock.instant()).abs()).isLessThan(TOLERANCE);
  }

  @Test
  void theOffsetIsAddedToTheSystemClock() {
    Duration offset = Duration.ofDays(40);
    Clock clock = configuration.creditClock(properties(offset));

    Duration ahead = Duration.between(Instant.now(), clock.instant());

    assertThat(ahead.minus(offset).abs()).isLessThan(TOLERANCE);
    assertThat(configuration.businessCalendar(clock, properties(offset)).today())
        .isEqualTo(java.time.LocalDate.now(ZoneId.of("America/Lima")).plusDays(40));
  }

  private static CreditProperties properties(Duration clockOffset) {
    return new CreditProperties(
        ZoneId.of("America/Lima"),
        clockOffset,
        new CreditProperties.Billing(new BigDecimal("0.05")),
        new CreditProperties.Messaging(
            "banking.credit.commands",
            "banking.transaction.replies",
            Duration.ofSeconds(1),
            3,
            Duration.ofSeconds(10)),
        new CreditProperties.Clients(
            new CreditProperties.Auth(
                URI.create("http://localhost:8080"),
                "credit-service",
                "secret",
                Duration.ofSeconds(60))));
  }
}
