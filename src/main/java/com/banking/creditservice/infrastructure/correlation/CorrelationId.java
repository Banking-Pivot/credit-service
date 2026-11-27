package com.banking.creditservice.infrastructure.correlation;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The identifier that follows one request through the API Gateway and every service it reaches, so
 * their log lines and error bodies can be matched.
 */
public final class CorrelationId {

  /** Header that carries the identifier between the caller, the gateway and the services. */
  public static final String HEADER = "X-Correlation-ID";

  /** Name of the identifier in the logs (MDC), in the Reactor Context and in the exchange. */
  public static final String KEY = "corrId";

  private static final Pattern USABLE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private CorrelationId() {}

  /**
   * The identifier to use for a request.
   *
   * @param received value of the header, or null when the caller sent none
   * @return the received value when it is short and has no characters that could break a log line;
   *     otherwise a new random one
   */
  public static String accept(String received) {
    return received != null && USABLE.matcher(received).matches()
        ? received
        : UUID.randomUUID().toString();
  }

  /**
   * The identifier of the request being served.
   *
   * @param exchange the current request and response
   * @return the identifier {@link CorrelationIdFilter} stored, or the request id of the server when
   *     the filter did not run
   */
  public static String of(ServerWebExchange exchange) {
    return exchange.getAttributeOrDefault(KEY, exchange.getRequest().getId());
  }

  /**
   * Copies the identifier of the request being served to the calls a WebClient makes.
   *
   * @return the filter to add to the WebClient
   */
  public static ExchangeFilterFunction propagation() {
    return (request, next) ->
        Mono.deferContextual(
            context ->
                next.exchange(
                    context
                        .<String>getOrEmpty(KEY)
                        .map(id -> ClientRequest.from(request).header(HEADER, id).build())
                        .orElse(request)));
  }
}
