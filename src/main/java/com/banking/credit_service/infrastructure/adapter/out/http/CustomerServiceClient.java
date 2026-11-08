package com.banking.credit_service.infrastructure.adapter.out.http;

import com.banking.credit_service.application.exception.DependencyUnavailableException;
import com.banking.credit_service.application.model.CustomerSnapshot;
import com.banking.credit_service.application.port.out.CustomerDirectoryPort;
import com.banking.credit_service.domain.model.CustomerType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Customer Service, called directly while the API Gateway and Eureka are pending:
 * {@code GET /customers/{customerId}} of {@code customer-api.yml} at
 * {@code clients.customer.base-url}. Pointing that URL at the Gateway later needs no code
 * change.
 *
 * <ul>
 *   <li>200: the customer, ACTIVE or INACTIVE.</li>
 *   <li>404 with code CUSTOMER_NOT_FOUND: the customer does not exist (empty result).</li>
 *   <li>Anything else, including a 404 without that code (for example a wrong base URL),
 *       a 5xx, a body outside the contract, no answer within the configured timeout or an
 *       open circuit: {@link DependencyUnavailableException}.</li>
 * </ul>
 *
 * <p>An unknown or inactive customer is a successful call for the circuit breaker; only
 * technical failures count against Customer Service.
 */
@Component
public class CustomerServiceClient implements CustomerDirectoryPort {

    public static final String CIRCUIT_BREAKER = "customer-service";
    static final String DEPENDENCY = "Customer Service";
    private static final String NOT_FOUND_CODE = "CUSTOMER_NOT_FOUND";

    private final WebClient customers;
    private final ReactiveCircuitBreakerFactory<?, ?> circuitBreakers;

    public CustomerServiceClient(WebClient customerWebClient, ReactiveCircuitBreakerFactory<?, ?> circuitBreakers) {
        this.customers = customerWebClient;
        this.circuitBreakers = circuitBreakers;
    }

    @Override
    public Mono<CustomerSnapshot> findById(UUID customerId) {
        Mono<CustomerSnapshot> call = customers.get()
                .uri("/customers/{customerId}", customerId)
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(this::read);
        // The breaker is looked up per call so it always uses the configured settings.
        return circuitBreakers.create(CIRCUIT_BREAKER)
                .run(call, failure -> Mono.error(new DependencyUnavailableException(DEPENDENCY, failure)));
    }

    private Mono<CustomerSnapshot> read(ClientResponse response) {
        if (response.statusCode().isSameCodeAs(HttpStatus.OK)) {
            return response.bodyToMono(CustomerPayload.class)
                    .switchIfEmpty(Mono.error(() -> new UnexpectedResponseException("Empty customer body.")))
                    .map(CustomerPayload::toSnapshot);
        }
        if (response.statusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return response.bodyToMono(ErrorPayload.class)
                    .defaultIfEmpty(new ErrorPayload(null))
                    .flatMap(error -> NOT_FOUND_CODE.equals(error.code())
                            ? Mono.<CustomerSnapshot>empty()
                            : Mono.error(new UnexpectedResponseException("404 without " + NOT_FOUND_CODE + ".")));
        }
        return response.releaseBody()
                .then(Mono.error(new UnexpectedResponseException("Status " + response.statusCode().value() + ".")));
    }

    /** Fields of {@code CustomerResponse} that Credit Service uses. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CustomerPayload(UUID id, String type, String status) {

        CustomerSnapshot toSnapshot() {
            boolean active = switch (String.valueOf(status)) {
                case "ACTIVE" -> true;
                case "INACTIVE" -> false;
                default -> throw new UnexpectedResponseException("Unknown customer status.");
            };
            CustomerType customerType = switch (String.valueOf(type)) {
                case "PERSONAL" -> CustomerType.PERSONAL;
                case "BUSINESS" -> CustomerType.BUSINESS;
                default -> throw new UnexpectedResponseException("Unknown customer type.");
            };
            if (id == null) {
                throw new UnexpectedResponseException("Customer without id.");
            }
            return new CustomerSnapshot(id, customerType, active);
        }
    }

    /** Code of {@code ErrorResponse}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorPayload(String code) {
    }
}
