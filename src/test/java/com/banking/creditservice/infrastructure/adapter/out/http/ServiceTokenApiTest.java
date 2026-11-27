package com.banking.creditservice.infrastructure.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.creditservice.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The service token in the whole service: opening a credit asks Customer Service through the API
 * Gateway, which needs a token. The stub refuses every call without a token it issued, so all the
 * other tests already prove the token is sent; these cover getting it, renewing it and what the
 * client of the API sees when it cannot be had.
 */
class ServiceTokenApiTest extends PostgresIntegrationTest {

  /** One token for many requests, and a new one only when the Gateway refuses the old one. */
  @Test
  void tokenIsReusedAndRenewedWhenTheGatewayRefusesIt() {
    openCard(personalCustomer(), "100.00");
    int tokensBefore = CUSTOMERS.tokenRequests();

    openCard(personalCustomer(), "100.00");
    openCard(businessCustomer(), "100.00");
    assertThat(CUSTOMERS.tokenRequests()).as("reused").isEqualTo(tokensBefore);

    CUSTOMERS.revokeTokens();
    openCard(personalCustomer(), "100.00");
    openCard(personalCustomer(), "100.00");

    assertThat(CUSTOMERS.tokenRequests()).as("renewed once").isEqualTo(tokensBefore + 1);
    assertThat(CUSTOMERS.unauthorizedCalls()).isEqualTo(1);
    assertThat(CUSTOMERS.calls()).isEqualTo(5);
    assertThat(creditCount()).isEqualTo(5);
  }

  /** The token request belongs to the same request of the client: same correlation id. */
  @Test
  void tokenRequestCarriesTheCorrelationIdentifier() {
    UUID customer = personalCustomer();
    CUSTOMERS.revokeTokens();

    openWithCorrelationId(customer, "token-trace-1").expectStatus().isCreated();

    assertThat(CUSTOMERS.tokenCorrelationIds()).containsExactly("token-trace-1");
    assertThat(CUSTOMERS.correlationIds()).containsExactly("token-trace-1");
  }

  /**
   * auth-service fails: the credit is not opened and the client reads 503, as for any dependency.
   */
  @ParameterizedTest
  @ValueSource(ints = {500, 503})
  void failingAuthServiceIsUnavailableDependency(int status) {
    UUID customer = personalCustomer();
    CUSTOMERS.revokeTokens();
    CUSTOMERS.failTokens(status);

    assertError(
        openRequest(customer, "CREDIT_CARD", "PEN", null, "100.00"), 503, "DEPENDENCY_UNAVAILABLE");

    assertThat(CUSTOMERS.calls()).as("Customer Service is never reached").isZero();
    assertThat(CUSTOMERS.tokenRequests()).as("a 5xx is retried like any other").isEqualTo(3);
    assertThat(creditCount()).isZero();
    assertThat(customerCircuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
  }

  /** The secret is not accepted: asking again would not help, so there is one request only. */
  @Test
  void refusedClientIsUnavailableDependencyWithoutRetries() {
    UUID customer = personalCustomer();
    CUSTOMERS.revokeTokens();
    CUSTOMERS.failTokens(401);

    assertError(
        openRequest(customer, "CREDIT_CARD", "PEN", null, "100.00"), 503, "DEPENDENCY_UNAVAILABLE");

    assertThat(CUSTOMERS.tokenRequests()).isEqualTo(1);
    assertThat(CUSTOMERS.calls()).isZero();
    assertThat(creditCount()).isZero();
  }

  /** A new token the Gateway refuses as well: 503 for the client, never its own 401. */
  @Test
  void tokenRefusedTwiceIsUnavailableDependency() {
    UUID customer = personalCustomer();
    CUSTOMERS.revokeTokens();
    CUSTOMERS.issueRejectedTokens();

    assertError(
        openRequest(customer, "CREDIT_CARD", "PEN", null, "100.00"), 503, "DEPENDENCY_UNAVAILABLE");

    assertThat(CUSTOMERS.calls()).isZero();
    assertThat(creditCount()).isZero();
  }

  /** Once auth-service answers again the next request works: the failure was not kept. */
  @Test
  void recoversWhenAuthServiceAnswersAgain() {
    UUID customer = personalCustomer();
    CUSTOMERS.revokeTokens();
    CUSTOMERS.failTokens(503);
    assertError(
        openRequest(customer, "CREDIT_CARD", "PEN", null, "100.00"), 503, "DEPENDENCY_UNAVAILABLE");

    CUSTOMERS.failTokens(0);

    assertThat(openCard(customer, "100.00").getId()).isNotNull();
  }

  private WebTestClient.ResponseSpec openWithCorrelationId(UUID customerId, String id) {
    return webTestClient
        .post()
        .uri("/api/v1/credits")
        .header("X-Correlation-ID", id)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            Map.of(
                "customerId",
                customerId,
                "type",
                "CREDIT_CARD",
                "currency",
                "PEN",
                "creditLimit",
                new BigDecimal("100.00"),
                "paymentDueDay",
                15))
        .exchange();
  }
}
