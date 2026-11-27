package com.banking.creditservice.application.port.out;

import com.banking.creditservice.application.model.CustomerSnapshot;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Customers of Customer Service, consulted only when a product is opened. */
public interface CustomerDirectoryPort {

  /**
   * The customer, ACTIVE or INACTIVE; empty when it does not exist. A timeout, a failure or an open
   * circuit is {@code DependencyUnavailableException}.
   */
  Mono<CustomerSnapshot> findById(UUID customerId);
}
