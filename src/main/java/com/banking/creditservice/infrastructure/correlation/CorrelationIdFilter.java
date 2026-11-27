package com.banking.creditservice.infrastructure.correlation;

import io.micrometer.context.ContextRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Gives every request its correlation identifier: the one received in {@code X-Correlation-ID} (the
 * API Gateway always sends it) or a new one. The identifier goes back in the same header, into the
 * error body, into the calls to other services and into every log line of the request.
 *
 * <p>A request hops between threads, so the identifier travels in the Reactor Context and Reactor
 * copies it to the MDC of whichever thread runs each step ({@code
 * spring.reactor.context-propagation=auto}).
 */
@Component
public class CorrelationIdFilter implements WebFilter, Ordered {

  private static final Logger LOG = LoggerFactory.getLogger(CorrelationIdFilter.class);

  static {
    ContextRegistry.getInstance()
        .registerThreadLocalAccessor(
            CorrelationId.KEY,
            () -> MDC.get(CorrelationId.KEY),
            id -> MDC.put(CorrelationId.KEY, id),
            () -> MDC.remove(CorrelationId.KEY));
  }

  /**
   * Resolves the identifier, exposes it and logs one line when the request ends.
   *
   * @param exchange the current request and response
   * @param chain the rest of the filters and the handler
   * @return completion of the request
   */
  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String id =
        CorrelationId.accept(exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER));
    exchange.getAttributes().put(CorrelationId.KEY, id);
    exchange.getResponse().getHeaders().set(CorrelationId.HEADER, id);
    long startedAt = System.nanoTime();
    return chain
        .filter(exchange)
        .doFinally(signal -> logRequest(exchange, id, startedAt))
        .contextWrite(context -> context.put(CorrelationId.KEY, id));
  }

  /** Before every other filter, so all of them log with the identifier. */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  // Method, path and status only: the query string, headers and body can carry personal data.
  private static void logRequest(ServerWebExchange exchange, String id, long startedAt) {
    if (LOG.isInfoEnabled()) {
      try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationId.KEY, id)) {
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        LOG.info(
            "{} {} -> {} in {} ms",
            exchange.getRequest().getMethod(),
            exchange.getRequest().getPath().value(),
            status == null ? "no status" : status.value(),
            (System.nanoTime() - startedAt) / 1_000_000);
      }
    }
  }
}
