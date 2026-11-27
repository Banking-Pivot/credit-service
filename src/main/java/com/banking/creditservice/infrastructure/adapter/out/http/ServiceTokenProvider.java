package com.banking.creditservice.infrastructure.adapter.out.http;

import com.banking.creditservice.infrastructure.config.CreditProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * The access token this service calls the others with. The API Gateway only lets a request through
 * with a JWT, so Credit Service asks auth-service for one of its own: {@code POST
 * /api/v1/auth/service-token} with its client id and the secret it reads from Vault.
 *
 * <ul>
 *   <li>The token is kept and reused until {@code credit.clients.auth.refresh-margin} before its
 *       expiry; callers that arrive while it is being requested wait for that one request.
 *   <li>A call answered 401 gets a new token and is sent once more. A second 401 goes back to the
 *       caller as it is.
 *   <li>A token that cannot be obtained is an error of the call that needed it, which {@link
 *       DependencyGuard} turns into "dependency unavailable" like any other failure.
 * </ul>
 *
 * <p>Neither the secret nor the token is ever logged.
 */
@Component
public class ServiceTokenProvider {

  static final String TOKEN_PATH = "/api/v1/auth/service-token";

  private static final Logger LOG = LoggerFactory.getLogger(ServiceTokenProvider.class);

  private final WebClient auth;
  private final CreditProperties.Auth settings;
  private final AtomicReference<Mono<AccessToken>> current = new AtomicReference<>();

  /**
   * Creates the provider; no token is requested until a call needs one.
   *
   * @param authWebClient client pointed at the API Gateway
   * @param properties client id, secret and refresh margin
   */
  public ServiceTokenProvider(
      @Qualifier("authWebClient") WebClient authWebClient, CreditProperties properties) {
    this.auth = authWebClient;
    this.settings = properties.clients().auth();
    this.current.set(lookup());
  }

  /**
   * Sends the token in the {@code Authorization} header of every call of a WebClient, and repeats
   * once, with a new token, a call answered 401.
   *
   * @return the filter to add to the WebClient
   */
  public ExchangeFilterFunction authorization() {
    return (request, next) ->
        token()
            .flatMap(
                token ->
                    next.exchange(authorized(request, token))
                        .flatMap(
                            response ->
                                response.statusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)
                                    ? response
                                        .releaseBody()
                                        .then(tokenAfterRejection(token))
                                        .flatMap(fresh -> next.exchange(authorized(request, fresh)))
                                    : Mono.just(response)));
  }

  /** The token to use now: the one kept, or a new one when there is none or it is about to end. */
  Mono<String> token() {
    return Mono.defer(current::get).map(AccessToken::value);
  }

  /**
   * The token to use after {@code rejected} got a 401: a new one, unless another call already
   * replaced it.
   */
  Mono<String> tokenAfterRejection(String rejected) {
    return Mono.defer(
            () -> {
              Mono<AccessToken> kept = current.get();
              return kept.flatMap(
                  token -> {
                    if (!token.value().equals(rejected)) {
                      return Mono.just(token);
                    }
                    LOG.info("The service token was rejected; requesting a new one.");
                    current.compareAndSet(kept, lookup());
                    return current.get();
                  });
            })
        .map(AccessToken::value);
  }

  // One request shared by everyone who subscribes while it runs; its token is replayed for as long
  // as it can be used, and a failure is not kept: the next caller asks again.
  private Mono<AccessToken> lookup() {
    return Mono.defer(this::request)
        .cache(AccessToken::usableFor, failure -> Duration.ZERO, () -> Duration.ZERO);
  }

  private Mono<AccessToken> request() {
    return auth.post()
        .uri(TOKEN_PATH)
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("clientId", settings.clientId(), "clientSecret", settings.clientSecret()))
        .exchangeToMono(this::read);
  }

  private Mono<AccessToken> read(ClientResponse response) {
    if (!response.statusCode().isSameCodeAs(HttpStatus.OK)) {
      return response
          .releaseBody()
          .then(
              Mono.error(
                  () ->
                      UnexpectedResponseException.ofStatus(
                          "Service token request", response.statusCode())));
    }
    return response
        .bodyToMono(TokenPayload.class)
        .switchIfEmpty(Mono.error(() -> new UnexpectedResponseException("Empty token body.")))
        .map(this::accessToken);
  }

  private AccessToken accessToken(TokenPayload payload) {
    if (payload.accessToken() == null || payload.accessToken().isBlank()) {
      throw new UnexpectedResponseException("Token response without accessToken.");
    }
    if (payload.expiresIn() == null || payload.expiresIn() <= 0) {
      throw new UnexpectedResponseException("Token response without expiresIn.");
    }
    Duration usableFor = Duration.ofSeconds(payload.expiresIn()).minus(settings.refreshMargin());
    return new AccessToken(
        payload.accessToken(), usableFor.isNegative() ? Duration.ZERO : usableFor);
  }

  private static ClientRequest authorized(ClientRequest request, String token) {
    return ClientRequest.from(request)
        .headers(headers -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .build();
  }

  /** A token and how long it can still be used, the refresh margin already taken off. */
  private record AccessToken(String value, Duration usableFor) {

    /** Without the token, so it never reaches a log. */
    @Override
    public String toString() {
      return "AccessToken[usableFor=" + usableFor + "]";
    }
  }

  /** Fields of the token response of auth-service that this service uses. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TokenPayload(String accessToken, Long expiresIn) {}
}
