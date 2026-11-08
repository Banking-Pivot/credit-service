package com.banking.credit_service.domain.exception;

import java.util.UUID;

/** The credit product is CLOSED and accepts no more changes. */
public class CreditClosedException extends RuntimeException {

    public CreditClosedException(UUID creditId) {
        super("Credit " + creditId + " is closed.");
    }
}
