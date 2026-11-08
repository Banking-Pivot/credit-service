package com.banking.credit_service.infrastructure.config;

import com.banking.credit_service.infrastructure.adapter.out.http.CustomerServiceClient;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Outbound HTTP: one WebClient pointed at Customer Service and the Resilience4j settings of
 * its circuit breaker (time limit per call and failure thresholds), all from
 * {@code clients.customer}.
 */
@Configuration
public class IntegrationConfiguration {

    @Bean
    public WebClient customerWebClient(WebClient.Builder builder, ClientsProperties properties) {
        return builder.clone()
                .baseUrl(properties.customer().baseUrl().toString())
                .build();
    }

    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> customerCircuitBreaker(ClientsProperties properties) {
        ClientsProperties.Customer customer = properties.customer();
        ClientsProperties.CircuitBreaker breaker = customer.circuitBreaker();
        TimeLimiterConfig timeLimiter = TimeLimiterConfig.custom()
                .timeoutDuration(customer.timeout())
                .cancelRunningFuture(true)
                .build();
        CircuitBreakerConfig circuitBreaker = CircuitBreakerConfig.custom()
                .failureRateThreshold(breaker.failureRateThreshold())
                .slidingWindowSize(breaker.slidingWindowSize())
                .minimumNumberOfCalls(breaker.minimumNumberOfCalls())
                .waitDurationInOpenState(breaker.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(breaker.permittedCallsInHalfOpenState())
                .build();
        return factory -> factory.configure(builder -> builder
                .timeLimiterConfig(timeLimiter)
                .circuitBreakerConfig(circuitBreaker), CustomerServiceClient.CIRCUIT_BREAKER);
    }
}
