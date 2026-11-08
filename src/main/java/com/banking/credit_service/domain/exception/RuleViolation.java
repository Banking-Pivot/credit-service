package com.banking.credit_service.domain.exception;

/** Business rules a well-formed request can break. The name is the stable API error code. */
public enum RuleViolation {
    CUSTOMER_NOT_FOUND,
    CUSTOMER_INACTIVE,
    PRODUCT_NOT_ALLOWED,
    INVALID_PRODUCT_TERMS,
    INVALID_AMOUNT,
    NOT_A_CREDIT_CARD,
    CURRENCY_MISMATCH,
    INSUFFICIENT_AVAILABLE_CREDIT,
    PAYMENT_EXCEEDS_DEBT,
    CREDIT_LIMIT_BELOW_DEBT,
    DEBT_NOT_ZERO
}
