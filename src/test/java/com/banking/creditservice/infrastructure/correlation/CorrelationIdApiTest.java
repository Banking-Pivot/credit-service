package com.banking.creditservice.infrastructure.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.banking.creditservice.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The correlation identifier of a request over HTTP: header, error body, outgoing calls, logs. */
class CorrelationIdApiTest extends PostgresIntegrationTest {

  private static final String RECEIVED = "order-2026.11_20-A";
  private static final String GUARD_LOGGER =
      "com.banking.creditservice.infrastructure.adapter.out.http.DependencyGuard";

  @Test
  void theIdentifierReceivedGoesBackInTheHeaderAndInTheErrorBody() {
    webTestClient
        .get()
        .uri("/api/v1/credits/{id}", UUID.randomUUID())
        .header(CorrelationId.HEADER, RECEIVED)
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectHeader()
        .valueEquals(CorrelationId.HEADER, RECEIVED)
        .expectBody()
        .jsonPath("$.traceId")
        .isEqualTo(RECEIVED);
  }

  @Test
  void aRequestWithoutIdentifierGetsANewOne() {
    String first = generatedFor(webTestClient.get().uri("/api/v1/credits"));
    String second = generatedFor(webTestClient.get().uri("/api/v1/credits"));

    assertThat(UUID.fromString(first)).isNotEqualTo(UUID.fromString(second));
  }

  @Test
  void anIdentifierThatCouldBreakALogLineIsReplaced() {
    String tooLong = "x".repeat(65);

    assertThat(generatedFor(headed(tooLong))).isNotEqualTo(tooLong);
    assertThat(generatedFor(headed("two words"))).isNotEqualTo("two words");
    assertThat(generatedFor(headed("{\"injected\":true}"))).doesNotContain("injected");
  }

  @Test
  void theCallToCustomerServiceCarriesTheIdentifier() {
    UUID customer = personalCustomer();
    webTestClient = webTestClient.mutate().defaultHeader(CorrelationId.HEADER, RECEIVED).build();

    openCard(customer, "100.00");

    assertThat(CUSTOMERS.correlationIds()).containsExactly(RECEIVED);
  }

  @Test
  void aRetriedCallKeepsTheIdentifierInTheCallAndInTheLog() {
    UUID customer = personalCustomer();
    CUSTOMERS.failTimes(503, 1);
    webTestClient = webTestClient.mutate().defaultHeader(CorrelationId.HEADER, RECEIVED).build();
    ListAppender<ILoggingEvent> retries = new ListAppender<>();
    retries.start();
    Logger guard = (Logger) LoggerFactory.getLogger(GUARD_LOGGER);
    guard.addAppender(retries);

    try {
      openCard(customer, "100.00");
    } finally {
      guard.detachAppender(retries);
    }

    assertThat(CUSTOMERS.correlationIds()).containsExactly(RECEIVED, RECEIVED);
    // The retry is logged from a timer thread: the identifier got there through the Reactor
    // Context, not through the thread that received the request.
    assertThat(retries.list)
        .hasSize(1)
        .allSatisfy(
            event ->
                assertThat(event.getMDCPropertyMap()).containsEntry(CorrelationId.KEY, RECEIVED));
  }

  private WebTestClient.RequestHeadersSpec<?> headed(String identifier) {
    return webTestClient.get().uri("/api/v1/credits").header(CorrelationId.HEADER, identifier);
  }

  private static String generatedFor(WebTestClient.RequestHeadersSpec<?> request) {
    String identifier =
        request
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(Void.class)
            .getResponseHeaders()
            .getFirst(CorrelationId.HEADER);
    assertThat(identifier).matches("[0-9a-f-]{36}");
    return identifier;
  }
}
