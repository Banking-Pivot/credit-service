package com.banking.credit_service.application.command;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Replaces the editable terms of a product.
 *
 * @param creditLimit     required for cards, not allowed for loans
 * @param expectedVersion revision from If-Match
 */
public record UpdateCreditCommand(UUID creditId, long expectedVersion, Integer paymentDueDay, BigDecimal creditLimit) {

    public UpdateCreditCommand {
        Objects.requireNonNull(creditId, "creditId");
    }
}
