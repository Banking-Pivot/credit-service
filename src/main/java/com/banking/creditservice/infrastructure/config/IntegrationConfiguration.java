package com.banking.creditservice.infrastructure.config;

import com.banking.creditservice.infrastructure.adapter.out.http.CustomerServiceClient;
import com.banking.creditservice.infrastructure.adapter.out.http.ServiceTokenProvider;
import com.banking.creditservice.infrastructure.correlation.CorrelationId;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Outbound HTTP: one WebClient pointed at Customer Service, which sends the service token on every
 * call, the WebClient that gets that token, and the Resilience4j settings of the circuit breaker
 * (time limit per call and failure thresholds) from {@code clients.customer}.
 */
@Configuration
public class IntegrationConfiguration {

  /**
   * WebClient for Customer Service: base URL from {@code clients.customer.base-url}, with the
   * correlation id of the request being served and the access token of this service copied to every
   * call.
   */
  @Bean
  public WebClient customerWebClient(
      WebClient.Builder builder, ClientsProperties properties, ServiceTokenProvider tokens) {
    return builder
        .clone()
        .baseUrl(properties.customer().baseUrl().toString())
        .filter(CorrelationId.propagation())
        .filter(tokens.authorization())
        .build();
  }

  /**
   * WebClient for the token request: base URL from {@code credit.clients.auth.base-url}, the API
   * Gateway, with the correlation id of the request being served.
   */
  @Bean
  public WebClient authWebClient(WebClient.Builder builder, CreditProperties properties) {
    return builder
        .clone()
        .baseUrl(properties.clients().auth().baseUrl().toString())
        .filter(CorrelationId.propagation())
        .build();
  }

  /**
   * Applies the time limit per call and the failure thresholds of {@code clients.customer} to the
   * circuit breaker named {@link CustomerServiceClient#CIRCUIT_BREAKER}.
   */
  @Bean
  public Customizer<ReactiveResilience4JCircuitBreakerFactory> customerCircuitBreaker(
      ClientsProperties properties) {
    ClientsProperties.Customer customer = properties.customer();
    ClientsProperties.CircuitBreaker breaker = customer.circuitBreaker();
    TimeLimiterConfig timeLimiter =
        TimeLimiterConfig.custom()
            .timeoutDuration(customer.timeout())
            .cancelRunningFuture(true)
            .build();
    CircuitBreakerConfig circuitBreaker =
        CircuitBreakerConfig.custom()
            .failureRateThreshold(breaker.failureRateThreshold())
            .slidingWindowSize(breaker.slidingWindowSize())
            .minimumNumberOfCalls(breaker.minimumNumberOfCalls())
            .waitDurationInOpenState(breaker.waitDurationInOpenState())
            .permittedNumberOfCallsInHalfOpenState(breaker.permittedCallsInHalfOpenState())
            .build();
    return factory ->
        factory.configure(
            builder -> builder.timeLimiterConfig(timeLimiter).circuitBreakerConfig(circuitBreaker),
            CustomerServiceClient.CIRCUIT_BREAKER);
  }
}
