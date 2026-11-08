package com.banking.credit_service.application.model;

import java.time.Instant;
import java.util.Objects;

/** Debt or available credit of a product as read at {@code asOf}. */
public record CreditBalanceView(CreditView credit, Instant asOf) {

    public CreditBalanceView {
        Objects.requireNonNull(credit, "credit");
        Objects.requireNonNull(asOf, "asOf");
    }
}
