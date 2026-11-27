package com.banking.creditservice.infrastructure.adapter.out.http;

import com.banking.creditservice.application.model.CustomerSnapshot;
import com.banking.creditservice.application.port.out.CustomerDirectoryPort;
import com.banking.creditservice.domain.model.CustomerType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Customer Service through the API Gateway: {@code GET /api/v1/customers/{customerId}} of {@code
 * customer-api.yml} at {@code clients.customer.base-url}, the Gateway's address. Every call carries
 * the access token of this service ({@link ServiceTokenProvider}), which the Gateway requires.
 *
 * <ul>
 *   <li>200: the customer, ACTIVE or INACTIVE.
 *   <li>404 with code CUSTOMER_NOT_FOUND: the customer does not exist (empty result).
 *   <li>Anything else, including a 404 without that code (for example a wrong base URL), a 5xx, a
 *       body outside the contract, no answer within the configured timeout, an open circuit, a
 *       token that could not be obtained or a 401 that a new token did not fix: {@code
 *       DependencyUnavailableException}.
 * </ul>
 *
 * <p>An unknown or inactive customer is a successful call for the circuit breaker; only technical
 * failures count against Customer Service.
 */
@Component
public class CustomerServiceClient implements CustomerDirectoryPort {

  public static final String CIRCUIT_BREAKER = "customer-service";
  static final String DEPENDENCY = "Customer Service";
  private static final String NOT_FOUND_CODE = "CUSTOMER_NOT_FOUND";

  private final WebClient customers;
  private final DependencyGuard guard;

  public CustomerServiceClient(
      @Qualifier("customerWebClient") WebClient customerWebClient, DependencyGuard guard) {
    this.customers = customerWebClient;
    this.guard = guard;
  }

  @Override
  public Mono<CustomerSnapshot> findById(UUID customerId) {
    Mono<CustomerSnapshot> call =
        customers
            .get()
            .uri("/api/v1/customers/{customerId}", customerId)
            .accept(MediaType.APPLICATION_JSON)
            .exchangeToMono(this::read);
    return guard.run(CIRCUIT_BREAKER, DEPENDENCY, call);
  }

  private Mono<CustomerSnapshot> read(ClientResponse response) {
    if (response.statusCode().isSameCodeAs(HttpStatus.OK)) {
      return response
          .bodyToMono(CustomerPayload.class)
          .switchIfEmpty(Mono.error(() -> new UnexpectedResponseException("Empty customer body.")))
          .map(CustomerPayload::toSnapshot);
    }
    if (response.statusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
      return response
          .bodyToMono(ErrorPayload.class)
          .defaultIfEmpty(new ErrorPayload(null))
          .flatMap(
              error ->
                  NOT_FOUND_CODE.equals(error.code())
                      ? Mono.<CustomerSnapshot>empty()
                      : Mono.error(
                          new UnexpectedResponseException("404 without " + NOT_FOUND_CODE + ".")));
    }
    return response
        .releaseBody()
        .then(Mono.error(UnexpectedResponseException.ofStatus(response.statusCode())));
  }

  /** Fields of {@code CustomerResponse} that Credit Service uses. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CustomerPayload(UUID id, String type, String status) {

    CustomerSnapshot toSnapshot() {
      boolean active = isActive();
      CustomerType customerType = customerType();
      if (id == null) {
        throw new UnexpectedResponseException("Customer without id.");
      }
      return new CustomerSnapshot(id, customerType, active);
    }

    private boolean isActive() {
      return switch (String.valueOf(status)) {
        case "ACTIVE" -> true;
        case "INACTIVE" -> false;
        default -> throw new UnexpectedResponseException("Unknown customer status.");
      };
    }

    private CustomerType customerType() {
      return switch (String.valueOf(type)) {
        case "PERSONAL" -> CustomerType.PERSONAL;
        case "BUSINESS" -> CustomerType.BUSINESS;
        default -> throw new UnexpectedResponseException("Unknown customer type.");
      };
    }
  }

  /** Code of {@code ErrorResponse}. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ErrorPayload(String code) {}
}
