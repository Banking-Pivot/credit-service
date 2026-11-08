package com.banking.credit_service.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One entry of the history of a credit product. Movements are only appended.
 *
 * @param operationId client key of a charge or payment, server key of a disbursement
 * @param debtAfter   outstanding debt right after this movement
 */
public record Movement(
        UUID id,
        UUID creditId,
        UUID operationId,
        MovementType type,
        BigDecimal amount,
        CurrencyCode currency,
        BigDecimal debtAfter,
        String description,
        Instant occurredAt
) {

    public Movement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(creditId, "creditId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(debtAfter, "debtAfter");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
