package com.banking.credit_service.application.exception;

import java.util.UUID;

/** The credit product in the request path does not exist. */
public class CreditNotFoundException extends RuntimeException {

    public CreditNotFoundException(UUID creditId) {
        super("Credit " + creditId + " does not exist.");
    }
}
