package com.banking.creditservice.infrastructure.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.banking.creditservice.infrastructure.config.CreditProperties;
import com.banking.creditservice.support.StubCustomerService;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The service token without the rest of the service: a stub plays the API Gateway, which issues
 * tokens and refuses the calls that do not carry a good one.
 */
class ServiceTokenProviderTest {

  private static final StubCustomerService GATEWAY = StubCustomerService.start();
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final Duration MARGIN = Duration.ofSeconds(60);

  private UUID customer;

  @BeforeEach
  void startClean() {
    GATEWAY.reset();
    customer = GATEWAY.addCustomer("PERSONAL", true);
  }

  @Test
  void noTokenIsRequestedUntilCallNeedsIt() {
    provider(MARGIN);

    assertThat(GATEWAY.tokenRequests()).isZero();
  }

  @Test
  void requestsTheTokenOnceAndSendsItOnEveryCall() {
    WebClient client = client(provider(MARGIN));

    assertThat(call(client)).isEqualTo(200);
    assertThat(call(client)).isEqualTo(200);
    assertThat(call(client)).isEqualTo(200);

    assertThat(GATEWAY.tokenRequests()).isEqualTo(1);
    assertThat(GATEWAY.calls()).isEqualTo(3);
    assertThat(GATEWAY.unauthorizedCalls()).isZero();
  }

  @Test
  void callsThatArriveTogetherShareOneTokenRequest() {
    WebClient client = client(provider(MARGIN));

    List<Integer> statuses =
        Flux.range(0, 8).flatMap(call -> status(client)).collectList().block(TIMEOUT);

    assertThat(statuses).hasSize(8).containsOnly(200);
    assertThat(GATEWAY.tokenRequests()).isEqualTo(1);
  }

  /** A token is dropped the refresh margin before its expiry and the next call gets a new one. */
  @Test
  void tokenIsReplacedBeforeItExpires() throws InterruptedException {
    GATEWAY.tokenLifetime(60);
    WebClient client = client(provider(Duration.ofMillis(59_700)));

    assertThat(call(client)).isEqualTo(200);
    assertThat(call(client)).isEqualTo(200);
    assertThat(GATEWAY.tokenRequests()).as("reused while it lasts").isEqualTo(1);

    Thread.sleep(400);
    assertThat(call(client)).isEqualTo(200);
    assertThat(GATEWAY.tokenRequests()).as("replaced once the margin is reached").isEqualTo(2);
  }

  /** A token that lives less than the margin is used for the call that asked for it only. */
  @Test
  void tokenShorterThanTheMarginIsNotKept() {
    GATEWAY.tokenLifetime(30);
    WebClient client = client(provider(MARGIN));

    assertThat(call(client)).isEqualTo(200);
    assertThat(call(client)).isEqualTo(200);

    assertThat(GATEWAY.tokenRequests()).isEqualTo(2);
  }

  /** The Gateway stopped accepting the token: one new token, the same call once more. */
  @Test
  void callAnsweredUnauthorizedIsRepeatedWithNewToken() {
    WebClient client = client(provider(MARGIN));
    assertThat(call(client)).isEqualTo(200);
    GATEWAY.revokeTokens();

    assertThat(call(client)).isEqualTo(200);

    assertThat(GATEWAY.tokenRequests()).isEqualTo(2);
    assertThat(GATEWAY.unauthorizedCalls()).isEqualTo(1);
    assertThat(GATEWAY.calls()).isEqualTo(2);

    assertThat(call(client)).as("the new token is the one kept").isEqualTo(200);
    assertThat(GATEWAY.tokenRequests()).isEqualTo(2);
  }

  /** The new token is refused too: the 401 goes back to the caller, with no third attempt. */
  @Test
  void secondUnauthorizedAnswerGoesBackToTheCaller() {
    WebClient client = client(provider(MARGIN));
    assertThat(call(client)).isEqualTo(200);
    GATEWAY.revokeTokens();
    GATEWAY.issueRejectedTokens();

    assertThat(call(client)).isEqualTo(401);

    assertThat(GATEWAY.tokenRequests()).isEqualTo(2);
    assertThat(GATEWAY.unauthorizedCalls()).isEqualTo(2);
    assertThat(GATEWAY.calls()).isEqualTo(1);
  }

  /** Two calls rejected with the same token ask for one new token between them. */
  @Test
  void callsRejectedTogetherShareTheNewToken() {
    WebClient client = client(provider(MARGIN));
    assertThat(call(client)).isEqualTo(200);
    GATEWAY.revokeTokens();

    List<Integer> statuses =
        Flux.range(0, 6).flatMap(call -> status(client)).collectList().block(TIMEOUT);

    assertThat(statuses).hasSize(6).containsOnly(200);
    assertThat(GATEWAY.tokenRequests()).isEqualTo(2);
  }

  @Test
  void wrongSecretIsFailureThatRetryingDoesNotFix() {
    WebClient client = client(provider(MARGIN, "another-secret"));

    StepVerifier.create(status(client))
        .expectErrorSatisfies(
            failure -> {
              assertThat(failure).isInstanceOf(UnexpectedResponseException.class);
              assertThat(failure).hasMessage("Service token request: status 401.");
              assertThat(((UnexpectedResponseException) failure).retryable()).isFalse();
              assertThat(failure.getMessage()).doesNotContain("another-secret");
            })
        .verify(TIMEOUT);
    assertThat(GATEWAY.calls()).isZero();
  }

  /** auth-service fails: the call fails, nothing is kept and the next call asks again. */
  @ParameterizedTest
  @ValueSource(ints = {500, 503})
  void failingTokenEndpointIsRetryableFailureAndIsNotKept(int status) {
    WebClient client = client(provider(MARGIN));
    GATEWAY.failTokens(status);

    StepVerifier.create(status(client))
        .expectErrorSatisfies(
            failure -> {
              assertThat(failure).isInstanceOf(UnexpectedResponseException.class);
              assertThat(((UnexpectedResponseException) failure).retryable()).isTrue();
            })
        .verify(TIMEOUT);
    assertThat(GATEWAY.calls()).isZero();

    GATEWAY.failTokens(0);
    assertThat(call(client)).isEqualTo(200);
    assertThat(GATEWAY.tokenRequests()).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"accessToken\":\"\",\"expiresIn\":3600}",
        "{\"accessToken\":\"abc\"}",
        "{\"accessToken\":\"abc\",\"expiresIn\":0}"
      })
  void tokenAnswerOutsideTheContractIsFailure(String body) {
    WebClient client = client(provider(MARGIN));
    GATEWAY.answerTokensWith(body);

    StepVerifier.create(status(client))
        .expectErrorSatisfies(
            failure -> {
              assertThat(failure).isInstanceOf(UnexpectedResponseException.class);
              assertThat(failure.getMessage()).startsWith("Token response without");
            })
        .verify(TIMEOUT);
    assertThat(GATEWAY.calls()).isZero();
  }

  @Test
  void unreachableGatewayIsFailureOfTheCall() {
    ServiceTokenProvider provider =
        new ServiceTokenProvider(
            WebClient.builder().baseUrl("http://localhost:1").build(),
            properties("http://localhost:1", MARGIN, StubCustomerService.CLIENT_SECRET));

    StepVerifier.create(status(client(provider))).expectError().verify(TIMEOUT);
    assertThat(GATEWAY.calls()).isZero();
  }

  // ----- helpers

  private static ServiceTokenProvider provider(Duration margin) {
    return provider(margin, StubCustomerService.CLIENT_SECRET);
  }

  private static ServiceTokenProvider provider(Duration margin, String secret) {
    return new ServiceTokenProvider(
        WebClient.builder().baseUrl(GATEWAY.url()).build(),
        properties(GATEWAY.url(), margin, secret));
  }

  private static CreditProperties properties(String baseUrl, Duration margin, String secret) {
    return new CreditProperties(
        ZoneId.of("America/Lima"),
        Duration.ZERO,
        new CreditProperties.Billing(new BigDecimal("0.05")),
        new CreditProperties.Messaging(
            "banking.credit.commands",
            "banking.transaction.replies",
            Duration.ofSeconds(1),
            3,
            Duration.ofSeconds(10)),
        new CreditProperties.Clients(
            new CreditProperties.Auth(
                URI.create(baseUrl), StubCustomerService.CLIENT_ID, secret, margin)));
  }

  /** A WebClient like the one of Customer Service: every call goes out with the token. */
  private static WebClient client(ServiceTokenProvider provider) {
    return WebClient.builder().baseUrl(GATEWAY.url()).filter(provider.authorization()).build();
  }

  private Mono<Integer> status(WebClient client) {
    return client
        .get()
        .uri("/api/v1/customers/{id}", customer)
        .exchangeToMono(
            response -> response.releaseBody().thenReturn(response.statusCode().value()));
  }

  private int call(WebClient client) {
    return status(client).block(TIMEOUT);
  }
}
