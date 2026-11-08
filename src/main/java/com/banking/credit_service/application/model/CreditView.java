package com.banking.credit_service.application.model;

import com.banking.credit_service.domain.model.Credit;
import com.banking.credit_service.domain.model.CreditStatus;
import com.banking.credit_service.domain.model.CreditType;
import com.banking.credit_service.domain.model.CurrencyCode;
import com.banking.credit_service.domain.model.CustomerType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read model of a credit product.
 *
 * @param principal       loans only, {@code null} for cards
 * @param creditLimit     cards only, {@code null} for loans
 * @param availableCredit cards only, {@code null} for loans
 * @param closedAt        {@code null} while ACTIVE
 * @param version         revision exposed as ETag
 */
public record CreditView(
        UUID id,
        UUID customerId,
        CustomerType customerType,
        CreditType type,
        CurrencyCode currency,
        BigDecimal principal,
        BigDecimal creditLimit,
        BigDecimal outstandingDebt,
        BigDecimal availableCredit,
        int paymentDueDay,
        CreditStatus status,
        Instant openedAt,
        Instant updatedAt,
        Instant closedAt,
        long version
) {

    public static CreditView from(Credit credit) {
        return new CreditView(credit.getId(), credit.getCustomerId(), credit.getCustomerType(), credit.getType(),
                credit.getCurrency(), credit.getPrincipal(), credit.getCreditLimit(), credit.getOutstandingDebt(),
                credit.availableCredit().orElse(null), credit.getPaymentDueDay(), credit.getStatus(),
                credit.getOpenedAt(), credit.getUpdatedAt(), credit.getClosedAt(), credit.getVersion());
    }

    public boolean isCard() {
        return type.isCard();
    }
}
