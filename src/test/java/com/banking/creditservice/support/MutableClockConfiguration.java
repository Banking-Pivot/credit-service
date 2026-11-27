package com.banking.creditservice.support;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the system clock with one the tests control. */
@TestConfiguration(proxyBeanMethods = false)
public class MutableClockConfiguration {

  /** Monday 16 November 2026, 10:00 in Lima. */
  public static final Instant START = Instant.parse("2026-11-16T15:00:00Z");

  @Bean
  @Primary
  MutableClock testClock() {
    return new MutableClock(START);
  }
}
