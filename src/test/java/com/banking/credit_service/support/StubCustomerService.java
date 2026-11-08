package com.banking.credit_service.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stand-in for Customer Service at {@code clients.customer.base-url}.
 *
 * <p>{@code GET /customers/{id}} answers like {@code customer-api.yml}: 200 with the full
 * CustomerResponse (fields Credit Service ignores included) or 404 with code
 * CUSTOMER_NOT_FOUND. A fault simulates a failing, slow or misrouted dependency.
 */
public final class StubCustomerService {

    private static final String CUSTOMERS = "/customers/";

    private final HttpServer server;
    private final Map<UUID, Customer> customers = new ConcurrentHashMap<>();
    private final AtomicReference<Fault> fault = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

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
        fault.set(new Fault(status, Duration.ZERO, null));
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

    public int calls() {
        return calls.get();
    }

    public void reset() {
        customers.clear();
        fault.set(null);
        calls.set(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            calls.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            Fault current = fault.get();
            if (current != null) {
                sleep(current.delay());
                if (current.body() != null) {
                    respond(exchange, current.status(), current.body());
                    return;
                }
                if (current.status() > 0) {
                    respond(exchange, current.status(), "{\"timestamp\":\"2026-11-16T15:00:00Z\",\"status\":"
                            + current.status() + ",\"error\":\"Upstream error\",\"path\":\"" + path + "\"}");
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

    private void customer(HttpExchange exchange, String rawId) throws IOException {
        Customer customer = customers.get(UUID.fromString(rawId));
        if (customer == null) {
            respond(exchange, 404, "{\"code\":\"CUSTOMER_NOT_FOUND\",\"message\":\"The customer does not exist.\","
                    + "\"timestamp\":\"2026-11-16T15:00:00Z\",\"path\":\"/customers/" + rawId + "\",\"traceId\":\"stub\"}");
            return;
        }
        String profile = "PERSONAL".equals(customer.type()) ? "STANDARD" : "SME";
        respond(exchange, 200, "{\"id\":\"" + rawId + "\",\"type\":\"" + customer.type() + "\",\"profile\":\""
                + profile + "\",\"documentType\":\"DNI\",\"documentNumber\":\"12345678\","
                + "\"name\":\"Test Customer\",\"status\":\"" + customer.status() + "\","
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

    private record Customer(String type, String status) {
    }

    private record Fault(int status, Duration delay, String body) {
    }
}
