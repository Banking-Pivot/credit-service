package com.banking.creditservice.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stand-in for the API Gateway as Credit Service sees it, at {@code clients.customer.base-url} and
 * {@code credit.clients.auth.base-url}: Customer Service behind it, and auth-service for the
 * service token the Gateway asks for.
 *
 * <p>{@code POST /api/v1/auth/service-token} with the client id and secret of Credit Service
 * answers a new token; anything else, 401 INVALID_CLIENT. Every other path needs {@code
 * Authorization: Bearer} with a token issued here and not revoked: without it the answer is the 401
 * of the Gateway, which never reaches Customer Service, so it does not count in {@link #calls()}.
 * Issued tokens survive {@link #reset()}, like the token the service keeps between requests.
 *
 * <p>{@code GET /api/v1/customers/{id}} answers like {@code customer-api.yml}: 200 with the full
 * CustomerResponse (fields Credit Service ignores included) or 404 with code CUSTOMER_NOT_FOUND. A
 * fault simulates a failing, slow or misrouted dependency.
 */
public final class StubCustomerService {

  /** Client id and secret the token endpoint accepts. */
  public static final String CLIENT_ID = "credit-service";

  public static final String CLIENT_SECRET = "test-credit-secret";

  private static final String CUSTOMERS = "/api/v1/customers/";
  private static final String TOKEN = "/api/v1/auth/service-token";
  private static final long DEFAULT_LIFETIME = 3600;

  private final HttpServer server;
  private final Map<UUID, Customer> customers = new ConcurrentHashMap<>();
  private final AtomicReference<Fault> fault = new AtomicReference<>();
  private final AtomicInteger calls = new AtomicInteger();

  /** Calls the current fault still applies to; negative means every call. */
  private final AtomicInteger faultyCalls = new AtomicInteger(-1);

  private final List<String> correlationIds = new CopyOnWriteArrayList<>();

  private final Set<String> validTokens = ConcurrentHashMap.newKeySet();
  private final AtomicInteger issuedTokens = new AtomicInteger();
  private final AtomicInteger tokenRequests = new AtomicInteger();
  private final AtomicInteger unauthorizedCalls = new AtomicInteger();
  private final AtomicInteger tokenFault = new AtomicInteger();
  private final AtomicLong tokenLifetime = new AtomicLong(DEFAULT_LIFETIME);
  private final List<String> tokenCorrelationIds = new CopyOnWriteArrayList<>();
  private volatile boolean issuingRejectedTokens;
  private volatile String tokenBody;

  private StubCustomerService() {
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/", this::handle);
    server.start();
  }

  public static StubCustomerService start() {
    return new StubCustomerService();
  }

  public String url() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  /** Registers a customer and returns its ID. */
  public UUID addCustomer(String type, boolean active) {
    UUID id = UUID.randomUUID();
    customers.put(id, new Customer(type, active ? "ACTIVE" : "INACTIVE"));
    return id;
  }

  /** Every call answers this status with a JSON body without a code. */
  public void fail(int status) {
    faultyCalls.set(-1);
    fault.set(new Fault(status, Duration.ZERO, null));
  }

  /** The next {@code times} calls answer this status; the ones after, normally. */
  public void failTimes(int status, int times) {
    fault.set(new Fault(status, Duration.ZERO, null));
    faultyCalls.set(times);
  }

  /** The X-Correlation-ID of every call received; "null" when a call had none. */
  public List<String> correlationIds() {
    return List.copyOf(correlationIds);
  }

  /** Every call waits before answering normally. */
  public void delay(Duration delay) {
    fault.set(new Fault(0, delay, null));
  }

  /** A proxy or wrong base URL: 404 in another format, without CUSTOMER_NOT_FOUND. */
  public void misroute() {
    fail(404);
  }

  /** Every call answers 200 with this body. */
  public void answer(String json) {
    fault.set(new Fault(200, Duration.ZERO, json));
  }

  /** Calls that reached Customer Service: the ones that carried a valid token. */
  public int calls() {
    return calls.get();
  }

  /** Requests for a service token since the last reset. */
  public int tokenRequests() {
    return tokenRequests.get();
  }

  /** Calls answered 401 because their token was missing, unknown or revoked. */
  public int unauthorizedCalls() {
    return unauthorizedCalls.get();
  }

  /** The X-Correlation-ID of every token request received; "null" when it had none. */
  public List<String> tokenCorrelationIds() {
    return List.copyOf(tokenCorrelationIds);
  }

  /** Every token issued so far stops being accepted, as if it had expired at the Gateway. */
  public void revokeTokens() {
    validTokens.clear();
  }

  /** The token endpoint answers this status instead of a token. */
  public void failTokens(int status) {
    tokenFault.set(status);
  }

  /** The token endpoint answers 200 with this body instead of a token. */
  public void answerTokensWith(String json) {
    tokenBody = json;
  }

  /** The tokens issued from now on are not accepted by the other paths. */
  public void issueRejectedTokens() {
    issuingRejectedTokens = true;
  }

  /** The tokens issued from now on say they expire after this many seconds. */
  public void tokenLifetime(long seconds) {
    tokenLifetime.set(seconds);
  }

  public void reset() {
    customers.clear();
    fault.set(null);
    faultyCalls.set(-1);
    calls.set(0);
    correlationIds.clear();
    tokenRequests.set(0);
    unauthorizedCalls.set(0);
    tokenFault.set(0);
    tokenLifetime.set(DEFAULT_LIFETIME);
    tokenCorrelationIds.clear();
    issuingRejectedTokens = false;
    tokenBody = null;
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().getPath();
      if (TOKEN.equals(path)) {
        token(exchange);
        return;
      }
      if (!authorized(exchange)) {
        unauthorizedCalls.incrementAndGet();
        respond(
            exchange,
            401,
            "{\"code\":\"UNAUTHORIZED\",\"message\":\"The access token is not valid.\"}");
        return;
      }
      calls.incrementAndGet();
      correlationIds.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Correlation-ID")));
      Fault current = fault.get();
      if (current != null && faultyCalls.get() >= 0 && faultyCalls.getAndDecrement() <= 0) {
        current = null;
      }
      if (current != null) {
        sleep(current.delay());
        if (current.body() != null) {
          respond(exchange, current.status(), current.body());
          return;
        }
        if (current.status() > 0) {
          respond(
              exchange,
              current.status(),
              "{\"timestamp\":\"2026-11-16T15:00:00Z\",\"status\":"
                  + current.status()
                  + ",\"error\":\"Upstream error\",\"path\":\""
                  + path
                  + "\"}");
          return;
        }
      }
      if (!path.startsWith(CUSTOMERS)) {
        respond(exchange, 404, "{\"status\":404,\"error\":\"Not Found\"}");
        return;
      }
      customer(exchange, path.substring(CUSTOMERS.length()));
    } catch (IOException clientGone) {
      // The caller timed out and closed the connection; nothing to answer.
    }
  }

  private boolean authorized(HttpExchange exchange) {
    String header = exchange.getRequestHeaders().getFirst("Authorization");
    return header != null
        && header.startsWith("Bearer ")
        && validTokens.contains(header.substring("Bearer ".length()));
  }

  private void token(HttpExchange exchange) throws IOException {
    tokenRequests.incrementAndGet();
    tokenCorrelationIds.add(
        String.valueOf(exchange.getRequestHeaders().getFirst("X-Correlation-ID")));
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    int status = tokenFault.get();
    if (status > 0) {
      respond(exchange, status, "{\"status\":" + status + ",\"error\":\"Upstream error\"}");
      return;
    }
    boolean known =
        "POST".equals(exchange.getRequestMethod())
            && body.contains("\"clientId\":\"" + CLIENT_ID + "\"")
            && body.contains("\"clientSecret\":\"" + CLIENT_SECRET + "\"");
    if (!known) {
      respond(
          exchange,
          401,
          "{\"code\":\"INVALID_CLIENT\",\"message\":\"Unknown client or wrong secret.\"}");
      return;
    }
    String scripted = tokenBody;
    if (scripted != null) {
      respond(exchange, 200, scripted);
      return;
    }
    String token = "stub-token-" + issuedTokens.incrementAndGet();
    if (!issuingRejectedTokens) {
      validTokens.add(token);
    }
    respond(
        exchange,
        200,
        "{\"accessToken\":\""
            + token
            + "\",\"tokenType\":\"Bearer\",\"expiresIn\":"
            + tokenLifetime.get()
            + ",\"roles\":[\"SERVICE\"]}");
  }

  private void customer(HttpExchange exchange, String rawId) throws IOException {
    Customer customer = customers.get(UUID.fromString(rawId));
    if (customer == null) {
      respond(
          exchange,
          404,
          "{\"code\":\"CUSTOMER_NOT_FOUND\",\"message\":\"The customer does not exist.\","
              + "\"timestamp\":\"2026-11-16T15:00:00Z\",\"path\":\"/api/v1/customers/"
              + rawId
              + "\",\"traceId\":\"stub\"}");
      return;
    }
    String profile = "PERSONAL".equals(customer.type()) ? "STANDARD" : "SME";
    respond(
        exchange,
        200,
        "{\"id\":\""
            + rawId
            + "\",\"type\":\""
            + customer.type()
            + "\",\"profile\":\""
            + profile
            + "\",\"documentType\":\"DNI\",\"documentNumber\":\"12345678\","
            + "\"name\":\"Test Customer\",\"status\":\""
            + customer.status()
            + "\","
            + "\"createdAt\":\"2026-01-01T00:00:00Z\",\"updatedAt\":\"2026-01-01T00:00:00Z\"}");
  }

  private static void respond(HttpExchange exchange, int status, String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(body);
    }
  }

  private static void sleep(Duration delay) {
    if (delay.isZero()) {
      return;
    }
    try {
      Thread.sleep(delay.toMillis());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private record Customer(String type, String status) {}

  private record Fault(int status, Duration delay, String body) {}
}
