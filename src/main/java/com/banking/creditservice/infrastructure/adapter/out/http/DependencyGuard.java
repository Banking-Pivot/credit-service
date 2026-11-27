package com.banking.creditservice.infrastructure.adapter.out.http;

import com.banking.creditservice.application.exception.DependencyUnavailableException;
import com.banking.creditservice.infrastructure.config.ClientsProperties;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.retry.event.RetryOnRetryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

/**
 * The Resilience4j chain the calls to Customer Service go through, from the inside out.
 *
 * <ol>
 *   <li>Retry with exponential backoff, only for failures that can pass on their own: the
 *       connection was refused or cut, or the answer was a 5xx.
 *   <li>Circuit breaker. It counts the result after the retries, so one request is one failure
 *       however many attempts it made.
 *   <li>Time limit for the whole call, retries and waits included: the caller never waits longer
 *       than {@code clients.customer.timeout}.
 *   <li>Fallback: any failure, a timeout or an open circuit becomes {@link
 *       DependencyUnavailableException}, which the API answers as 503.
 * </ol>
 *
 * <p>Every call guarded here is a GET, so repeating it changes nothing in the other service.
 */
@Component
class DependencyGuard {

  private static final Logger LOG = LoggerFactory.getLogger(DependencyGuard.class);

  private final ReactiveCircuitBreakerFactory<?, ?> circuitBreakers;
  private final RetryRegistry retries;

  DependencyGuard(
      ReactiveCircuitBreakerFactory<?, ?> circuitBreakers, ClientsProperties properties) {
    this.circuitBreakers = circuitBreakers;
    ClientsProperties.Retry retry = properties.customer().retry();
    this.retries =
        RetryRegistry.of(
            RetryConfig.custom()
                .maxAttempts(retry.maxAttempts())
                .intervalFunction(
                    IntervalFunction.ofExponentialBackoff(
                        retry.waitDuration(), retry.backoffMultiplier()))
                .retryOnException(DependencyGuard::passesOnItsOwn)
                .build());
    this.retries
        .getEventPublisher()
        .onEntryAdded(
            added -> added.getAddedEntry().getEventPublisher().onRetry(DependencyGuard::logRetry));
  }

  /**
   * Runs the call with retry, circuit breaker, time limit and fallback.
   *
   * @param instance name of the circuit breaker and of the retry
   * @param dependency name of the dependency in the error the caller receives
   * @param call the request to the other service, not yet subscribed
   */
  <T> Mono<T> run(String instance, String dependency, Mono<T> call) {
    // Looked up per call so they always use the configured settings.
    return circuitBreakers
        .create(instance)
        .run(
            call.transformDeferred(RetryOperator.of(retries.retry(instance))),
            failure -> Mono.error(new DependencyUnavailableException(dependency, failure)));
  }

  private static void logRetry(RetryOnRetryEvent event) {
    LOG.warn(
        "Retrying {}. attempt={}, failureType={}",
        event.getName(),
        event.getNumberOfRetryAttempts(),
        event.getLastThrowable().getClass().getSimpleName());
  }

  private static boolean passesOnItsOwn(Throwable failure) {
    return failure instanceof WebClientRequestException
        || (failure instanceof UnexpectedResponseException response && response.retryable());
  }
}
