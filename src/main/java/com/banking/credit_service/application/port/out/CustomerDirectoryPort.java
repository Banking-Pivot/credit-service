package com.banking.credit_service.application.port.out;

import com.banking.credit_service.application.model.CustomerSnapshot;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Customers of Customer Service, consulted only when a product is opened. */
public interface CustomerDirectoryPort {

    /**
     * The customer, ACTIVE or INACTIVE; empty when it does not exist. A timeout, a failure
     * or an open circuit is {@code DependencyUnavailableException}.
     */
    Mono<CustomerSnapshot> findById(UUID customerId);
}
