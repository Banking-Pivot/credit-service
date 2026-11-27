package com.banking.creditservice.infrastructure.adapter.out.http;

import org.springframework.http.HttpStatusCode;

/**
 * A dependency answered something outside its contract: an unexpected status, an empty body or a
 * 404 that is not the documented "not found". It counts as a failure for the circuit breaker and
 * becomes {@code DependencyUnavailableException}.
 */
class UnexpectedResponseException extends RuntimeException {

  private final boolean retryable;

  UnexpectedResponseException(String message) {
    this(message, false);
  }

  private UnexpectedResponseException(String message, boolean retryable) {
    super(message);
    this.retryable = retryable;
  }

  /** An unexpected status. Only a 5xx is worth a retry: a 4xx answers the same next time. */
  static UnexpectedResponseException ofStatus(HttpStatusCode status) {
    return new UnexpectedResponseException(
        "Status " + status.value() + ".", status.is5xxServerError());
  }

  /** An unexpected status of a named request, with the same rule for retries. */
  static UnexpectedResponseException ofStatus(String request, HttpStatusCode status) {
    return new UnexpectedResponseException(
        request + ": status " + status.value() + ".", status.is5xxServerError());
  }

  boolean retryable() {
    return retryable;
  }
}
