package com.banking.creditservice.domain.model.vo;

import com.banking.creditservice.domain.model.enums.CustomerType;
import java.util.Objects;
import java.util.UUID;

/** The customer opening a product, as Customer Service reported it at opening time. */
public record CreditApplicant(UUID customerId, CustomerType type, boolean active) {

  public CreditApplicant {
    Objects.requireNonNull(customerId, "customerId");
    Objects.requireNonNull(type, "type");
  }
}
