package com.banking.credit_service.domain.model;

import java.math.BigDecimal;

/**
 * Entries of the history of a product. DISBURSEMENT is the opening debt of a loan; CHARGE
 * and PAYMENT come from operations.
 */
public enum MovementType {
    DISBURSEMENT,
    CHARGE,
    PAYMENT;

    /** How a movement of this type changes the outstanding debt. */
    public BigDecimal debtChange(BigDecimal amount) {
        return this == PAYMENT ? amount.negate() : amount;
    }
}
