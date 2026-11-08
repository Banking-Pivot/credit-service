package com.banking.credit_service.infrastructure.adapter.out.http;

import com.banking.credit_service.application.model.CustomerSnapshot;
import com.banking.credit_service.domain.model.CustomerType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client against the real Customer Service, not a stub: it reads what
 * customer-api.yml really answers for an active customer, an inactive one and an unknown
 * ID. Runs only when {@code LIVE_CUSTOMER_API_BASE_URL} points at a running Customer
 * Service (for example http://localhost:8081); it creates and deactivates one customer.
 */
@EnabledIfEnvironmentVariable(named = "LIVE_CUSTOMER_API_BASE_URL", matches = "https?://.+")
class CustomerServiceClientLiveTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient customerService = WebClient.builder()
            .baseUrl(System.getenv("LIVE_CUSTOMER_API_BASE_URL"))
            .build();

    private final CustomerServiceClient client = new CustomerServiceClient(customerService,
            new ReactiveResilience4JCircuitBreakerFactory(CircuitBreakerRegistry.ofDefaults(),
                    TimeLimiterRegistry.ofDefaults(), null, new Resilience4JConfigurationProperties()));

    @Test
    void readsActiveInactiveAndUnknownCustomers() {
        String document = String.valueOf(ThreadLocalRandom.current().nextLong(10_000_000L, 100_000_000L));
        var created = customerService.post().uri("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "BUSINESS", "profile", "SME", "documentType", "RUC",
                        "documentNumber", "20" + document + "1", "name", "Credit Live Test SAC"))
                .retrieve()
                .toEntity(CustomerId.class)
                .block(TIMEOUT);
        UUID customerId = created.getBody().id();

        CustomerSnapshot active = client.findById(customerId).block(TIMEOUT);
        assertThat(active).isEqualTo(new CustomerSnapshot(customerId, CustomerType.BUSINESS, true));

        customerService.delete().uri("/customers/{id}", customerId)
                .header(HttpHeaders.IF_MATCH, created.getHeaders().getETag())
                .retrieve()
                .toBodilessEntity()
                .block(TIMEOUT);
        CustomerSnapshot inactive = client.findById(customerId).block(TIMEOUT);
        assertThat(inactive).isEqualTo(new CustomerSnapshot(customerId, CustomerType.BUSINESS, false));

        assertThat(client.findById(UUID.randomUUID()).blockOptional(TIMEOUT)).as("unknown customer").isEmpty();
    }

    private record CustomerId(UUID id) {
    }
}
