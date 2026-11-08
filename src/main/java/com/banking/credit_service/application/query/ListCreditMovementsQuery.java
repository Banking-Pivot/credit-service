package com.banking.credit_service.application.query;

import java.util.Objects;
import java.util.UUID;

/** Movement history of a product, newest first. */
public record ListCreditMovementsQuery(UUID creditId, PageRequest pageRequest) {

    public ListCreditMovementsQuery {
        Objects.requireNonNull(creditId, "creditId");
        Objects.requireNonNull(pageRequest, "pageRequest");
    }
}
