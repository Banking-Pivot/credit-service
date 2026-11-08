package com.banking.credit_service.support;

import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.AvailableBalanceResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CreditDebtResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.CreditResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.MovementPageResponse;
import com.banking.credit_service.infrastructure.adapter.in.rest.generated.model.OperationResponse;
import com.banking.credit_service.infrastructure.adapter.out.http.CustomerServiceClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole service on a random port against a real PostgreSQL in Docker.
 *
 * <p>The application keeps its real {@code spring.config.import}. A stub Config Server serves
 * {@code stub-config/credit-service.yml}, the R2DBC location of the container and the URL of
 * a stub Customer Service; a real Vault holds the database user and password, which the
 * Config Server never serves. All of them start once, before any Spring context; schema.sql
 * creates the tables in the empty container at startup.
 *
 * <p>Every test starts with empty tables, a fresh Customer Service, a closed circuit
 * breaker and the clock at {@link MutableClockConfiguration#START}, and ends with
 * {@link LedgerAssertions#assertConsistent}: debts, movements and operations agree.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(MutableClockConfiguration.class)
public abstract class PostgresIntegrationTest {

    protected static final String DATABASE = "credits_db";
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine").withDatabaseName(DATABASE);
    protected static final StubCustomerService CUSTOMERS;
    protected static final StubConfigServer CONFIG_SERVER;
    protected static final TestVault VAULT;

    static {
        POSTGRES.start();
        CUSTOMERS = StubCustomerService.start();
        // Read by application.yaml through VAULT_URI and VAULT_APPROLE_FILE.
        VAULT = TestVault.start("credit-service", Map.of(
                "spring.r2dbc.username", POSTGRES.getUsername(),
                "spring.r2dbc.password", POSTGRES.getPassword()));
        Map<String, String> properties = new HashMap<>(yaml("stub-config/credit-service.yml"));
        properties.put("spring.r2dbc.url", r2dbcUrl());
        properties.put("clients.customer.base-url", CUSTOMERS.url());
        // No registry in the tests: the service must not register with a real Eureka.
        properties.put("eureka.client.enabled", "false");
        CONFIG_SERVER = StubConfigServer.start(properties);
        // Read by application.yaml: configserver:${CONFIG_SERVER_URL:http://localhost:8888}
        System.setProperty("CONFIG_SERVER_URL", CONFIG_SERVER.url());
    }

    @Autowired
    protected WebTestClient webTestClient;

    @Autowired
    protected DatabaseClient databaseClient;

    @Autowired
    protected MutableClock clock;

    @Autowired
    private ReactiveResilience4JCircuitBreakerFactory circuitBreakers;

    @BeforeEach
    void startClean() {
        databaseClient.sql("TRUNCATE credit_operations, credit_movements, credits").then().block();
        CUSTOMERS.reset();
        clock.set(MutableClockConfiguration.START);
        circuitBreakers.getCircuitBreakerRegistry().getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        webTestClient = webTestClient.mutate().responseTimeout(Duration.ofSeconds(15)).build();
    }

    @AfterEach
    void ledgerStaysConsistent() {
        LedgerAssertions.assertConsistent(databaseClient);
    }

    protected static String r2dbcUrl() {
        return "r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + DATABASE;
    }

    /**
     * The circuit breaker of the Customer Service calls. The factory creates it on the first
     * call with the configured settings; asking the registry first would create it with the
     * Resilience4j defaults, so a test that needs it before any call creates it through the
     * factory and resets it to drop that call.
     */
    protected CircuitBreaker customerCircuitBreaker() {
        String name = CustomerServiceClient.CIRCUIT_BREAKER;
        if (circuitBreakers.getCircuitBreakerRegistry().find(name).isEmpty()) {
            circuitBreakers.create(name).run(Mono.just(0)).block();
            circuitBreakers.getCircuitBreakerRegistry().circuitBreaker(name).reset();
        }
        return circuitBreakers.getCircuitBreakerRegistry().circuitBreaker(name);
    }

    // ----- customers in the stub Customer Service

    protected UUID personalCustomer() {
        return CUSTOMERS.addCustomer("PERSONAL", true);
    }

    protected UUID businessCustomer() {
        return CUSTOMERS.addCustomer("BUSINESS", true);
    }

    // ----- HTTP helpers

    protected WebTestClient.ResponseSpec post(String uri, Object body) {
        return webTestClient.post().uri(uri).contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    protected WebTestClient.ResponseSpec postJson(String uri, String json) {
        return webTestClient.post().uri(uri).contentType(MediaType.APPLICATION_JSON).bodyValue(json).exchange();
    }

    /** POST /credits; principal and creditLimit are left out when null. */
    protected WebTestClient.ResponseSpec openRequest(
            UUID customerId,
            String type,
            String currency,
            String principal,
            String creditLimit
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", customerId);
        body.put("type", type);
        body.put("currency", currency);
        if (principal != null) {
            body.put("principal", new BigDecimal(principal));
        }
        if (creditLimit != null) {
            body.put("creditLimit", new BigDecimal(creditLimit));
        }
        body.put("paymentDueDay", 15);
        return post("/credits", body);
    }

    protected CreditResponse open(WebTestClient.ResponseSpec response) {
        return response.expectStatus().isCreated()
                .expectBody(CreditResponse.class)
                .returnResult()
                .getResponseBody();
    }

    protected CreditResponse openCard(UUID customerId, String creditLimit) {
        return open(openRequest(customerId, "CREDIT_CARD", "PEN", null, creditLimit));
    }

    protected CreditResponse openPersonalLoan(UUID customerId, String principal) {
        return open(openRequest(customerId, "PERSONAL_LOAN", "PEN", principal, null));
    }

    protected CreditResponse openBusinessLoan(UUID customerId, String principal) {
        return open(openRequest(customerId, "BUSINESS_LOAN", "PEN", principal, null));
    }

    protected WebTestClient.ResponseSpec chargeRequest(UUID creditId, UUID operationId, String amount, String currency) {
        return post("/credits/" + creditId + "/charges", Map.of("operationId", operationId,
                "amount", new BigDecimal(amount), "currency", currency));
    }

    protected WebTestClient.ResponseSpec chargeRequest(UUID creditId, UUID operationId, String amount) {
        return chargeRequest(creditId, operationId, amount, "PEN");
    }

    protected WebTestClient.ResponseSpec payRequest(UUID creditId, UUID operationId, String amount, String currency) {
        return post("/credits/" + creditId + "/payments", Map.of("operationId", operationId,
                "amount", new BigDecimal(amount), "currency", currency));
    }

    protected WebTestClient.ResponseSpec payRequest(UUID creditId, UUID operationId, String amount) {
        return payRequest(creditId, operationId, amount, "PEN");
    }

    protected OperationResponse charge(UUID creditId, String amount) {
        return created(chargeRequest(creditId, UUID.randomUUID(), amount));
    }

    protected OperationResponse pay(UUID creditId, String amount) {
        return created(payRequest(creditId, UUID.randomUUID(), amount));
    }

    protected static OperationResponse created(WebTestClient.ResponseSpec response) {
        return response.expectStatus().isCreated()
                .expectHeader().doesNotExist("Idempotent-Replayed")
                .expectBody(OperationResponse.class)
                .returnResult()
                .getResponseBody();
    }

    protected static OperationResponse replayed(WebTestClient.ResponseSpec response) {
        return response.expectStatus().isOk()
                .expectHeader().valueEquals("Idempotent-Replayed", "true")
                .expectBody(OperationResponse.class)
                .returnResult()
                .getResponseBody();
    }

    protected static void assertError(WebTestClient.ResponseSpec response, int status, String code) {
        response.expectStatus().isEqualTo(status).expectBody().jsonPath("$.code").isEqualTo(code);
    }

    protected static void assertRuleViolation(WebTestClient.ResponseSpec response, String code) {
        assertError(response, 422, code);
    }

    protected CreditResponse credit(UUID creditId) {
        return webTestClient.get().uri("/credits/{id}", creditId).exchange()
                .expectStatus().isOk()
                .expectBody(CreditResponse.class)
                .returnResult()
                .getResponseBody();
    }

    protected String etag(UUID creditId) {
        return webTestClient.get().uri("/credits/{id}", creditId).exchange()
                .expectStatus().isOk()
                .expectBody(Void.class)
                .returnResult()
                .getResponseHeaders()
                .getETag();
    }

    protected CreditDebtResponse debt(UUID creditId) {
        return webTestClient.get().uri("/credits/{id}/debt", creditId).exchange()
                .expectStatus().isOk()
                .expectBody(CreditDebtResponse.class)
                .returnResult()
                .getResponseBody();
    }

    protected AvailableBalanceResponse available(UUID creditId) {
        return webTestClient.get().uri("/credits/{id}/available-balance", creditId).exchange()
                .expectStatus().isOk()
                .expectBody(AvailableBalanceResponse.class)
                .returnResult()
                .getResponseBody();
    }

    protected MovementPageResponse movements(UUID creditId) {
        return webTestClient.get().uri("/credits/{id}/movements?size=100", creditId).exchange()
                .expectStatus().isOk()
                .expectBody(MovementPageResponse.class)
                .returnResult()
                .getResponseBody();
    }

    // ----- direct database reads, independent of the API

    protected BigDecimal storedDebt(UUID creditId) {
        return databaseClient.sql("SELECT outstanding_debt FROM credits WHERE id = :id")
                .bind("id", creditId)
                .map(row -> row.get("outstanding_debt", BigDecimal.class))
                .one()
                .block();
    }

    protected long count(String sql, Map<String, Object> binds) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql);
        for (Map.Entry<String, Object> bind : binds.entrySet()) {
            spec = spec.bind(bind.getKey(), bind.getValue());
        }
        return spec.map(row -> row.get(0, Long.class)).one().block();
    }

    protected long movementCount(UUID creditId) {
        return count("SELECT COUNT(*) FROM credit_movements WHERE credit_id = :id", Map.of("id", creditId));
    }

    protected long creditCount() {
        return count("SELECT COUNT(*) FROM credits", Map.of());
    }

    protected long operationCount() {
        return count("SELECT COUNT(*) FROM credit_operations", Map.of());
    }

    protected static void assertMoney(BigDecimal actual, String expected) {
        assertThat(actual).isEqualByComparingTo(expected);
    }

    private static Map<String, String> yaml(String resource) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(resource));
        Properties properties = factory.getObject();
        Map<String, String> values = new HashMap<>();
        properties.forEach((key, value) -> values.put(key.toString(), value.toString()));
        return values;
    }
}
