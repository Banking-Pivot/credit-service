package com.banking.credit_service.application.query;

import java.util.Objects;
import java.util.UUID;

/** Available credit of a card. */
public record GetAvailableCreditQuery(UUID creditId) {

    public GetAvailableCreditQuery {
        Objects.requireNonNull(creditId, "creditId");
    }
}
