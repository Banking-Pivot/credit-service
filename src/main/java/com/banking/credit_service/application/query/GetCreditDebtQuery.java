package com.banking.credit_service.application.query;

import java.util.Objects;
import java.util.UUID;

/** Outstanding debt of a loan or card. */
public record GetCreditDebtQuery(UUID creditId) {

    public GetCreditDebtQuery {
        Objects.requireNonNull(creditId, "creditId");
    }
}
