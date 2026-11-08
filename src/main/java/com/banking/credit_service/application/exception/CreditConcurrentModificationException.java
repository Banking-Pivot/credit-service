package com.banking.credit_service.application.exception;

import java.util.UUID;

/** The revision sent in If-Match is not the current revision of the product. */
public class CreditConcurrentModificationException extends RuntimeException {

    public CreditConcurrentModificationException(UUID creditId) {
        super("Credit " + creditId + " changed since it was read.");
    }
}
