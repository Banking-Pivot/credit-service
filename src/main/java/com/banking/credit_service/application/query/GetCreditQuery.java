package com.banking.credit_service.application.query;

import java.util.Objects;
import java.util.UUID;

/** One product by ID, ACTIVE or CLOSED. */
public record GetCreditQuery(UUID creditId) {

    public GetCreditQuery {
        Objects.requireNonNull(creditId, "creditId");
    }
}
