package com.banking.credit_service.domain.model;

import java.util.Objects;
import java.util.UUID;

/** The customer opening a product, as Customer Service reported it at opening time. */
public record CreditApplicant(UUID customerId, CustomerType type, boolean active) {

    public CreditApplicant {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(type, "type");
    }
}
