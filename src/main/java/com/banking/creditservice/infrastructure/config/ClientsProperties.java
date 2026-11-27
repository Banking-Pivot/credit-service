package com.banking.creditservice.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code clients.*} settings, served by the Config Server ({@code credit-service.yml}): where other
 * services are and how long to wait for them.
 */
@Validated
@ConfigurationProperties("clients")
public record ClientsProperties(@NotNull @Valid Customer customer) {

  /**
   * Customer Service, reached through the API Gateway: the base URL is the Gateway's ({@code
   * CUSTOMER_API_BASE_URL}).
   *
   * @param timeout limit per call, retries included, before the answer counts as unavailable
   */
  public record Customer(
      @NotNull URI baseUrl,
      @NotNull @DefaultValue("2s") Duration timeout,
      @NotNull @Valid @DefaultValue CircuitBreaker circuitBreaker,
      @NotNull @Valid @DefaultValue Retry retry) {}

  /**
   * Retries of a call that failed fast (connection refused, 5xx answer), within the timeout of the
   * call: the wait grows by the multiplier after every attempt.
   *
   * @param maxAttempts attempts in total, the first one included
   * @param waitDuration wait before the second attempt
   */
  public record Retry(
      @DefaultValue("3") @Min(1) int maxAttempts,
      @DefaultValue("200ms") @NotNull Duration waitDuration,
      @DefaultValue("2") @DecimalMin("1") double backoffMultiplier) {}

  /** Resilience4j circuit breaker of the Customer Service calls. */
  public record CircuitBreaker(
      @DefaultValue("50") @Min(1) @Max(100) float failureRateThreshold,
      @DefaultValue("10") @Min(1) int slidingWindowSize,
      @DefaultValue("5") @Min(1) int minimumNumberOfCalls,
      @DefaultValue("10s") @NotNull Duration waitDurationInOpenState,
      @DefaultValue("2") @Min(1) int permittedCallsInHalfOpenState) {}
}
