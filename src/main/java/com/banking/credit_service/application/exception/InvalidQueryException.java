package com.banking.credit_service.application.exception;

/** Query parameters that are well-formed one by one but inconsistent together. */
public class InvalidQueryException extends RuntimeException {

    public InvalidQueryException(String message) {
        super(message);
    }
}
