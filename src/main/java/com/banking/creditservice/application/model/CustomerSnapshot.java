package com.banking.creditservice.application.model;

import com.banking.creditservice.domain.model.CreditApplicant;
import com.banking.creditservice.domain.model.CustomerType;
import java.util.Objects;
import java.util.UUID;

/** What Credit Service needs from a customer of Customer Service, read when a product is opened. */
public record CustomerSnapshot(UUID id, CustomerType type, boolean active) {

  public CustomerSnapshot {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
  }

  public CreditApplicant toApplicant() {
    return new CreditApplicant(id, type, active);
  }
}
