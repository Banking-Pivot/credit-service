package com.banking.credit_service.domain.model;

/** A product is ACTIVE until it is closed; closing is logical and final. */
public enum CreditStatus {
    ACTIVE,
    CLOSED
}
