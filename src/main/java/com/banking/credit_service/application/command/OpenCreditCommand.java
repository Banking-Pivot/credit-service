package com.banking.credit_service.application.command;

import com.banking.credit_service.domain.model.CreditType;
import com.banking.credit_service.domain.model.CurrencyCode;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Opens a credit product.
 *
 * @param principal   loans only
 * @param creditLimit cards only
 */
public record OpenCreditCommand(
        UUID customerId,
        CreditType type,
        CurrencyCode currency,
        BigDecimal principal,
        BigDecimal creditLimit,
        Integer paymentDueDay
) {

    public OpenCreditCommand {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(currency, "currency");
    }
}
