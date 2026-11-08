package com.banking.credit_service.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * {@code clients.*} settings, served by the Config Server ({@code credit-service.yml}):
 * where other services are and how long to wait for them.
 */
@Validated
@ConfigurationProperties("clients")
public record ClientsProperties(@NotNull @Valid Customer customer) {

    /**
     * Customer Service, reached through the API Gateway: the base URL is the Gateway's
     * ({@code CUSTOMER_API_BASE_URL}).
     *
     * @param timeout limit per call before the answer counts as unavailable
     */
    public record Customer(
            @NotNull URI baseUrl,
            @NotNull @DefaultValue("2s") Duration timeout,
            @NotNull @Valid @DefaultValue CircuitBreaker circuitBreaker
    ) {
    }

    /** Resilience4j circuit breaker of the Customer Service calls. */
    public record CircuitBreaker(
            @DefaultValue("50") @Min(1) @Max(100) float failureRateThreshold,
            @DefaultValue("10") @Min(1) int slidingWindowSize,
            @DefaultValue("5") @Min(1) int minimumNumberOfCalls,
            @DefaultValue("10s") @NotNull Duration waitDurationInOpenState,
            @DefaultValue("2") @Min(1) int permittedCallsInHalfOpenState
    ) {
    }
}
