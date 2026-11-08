package com.banking.credit_service.application.exception;

/** The customer already has an ACTIVE personal loan; the partial unique index decided it. */
public class DuplicatePersonalLoanException extends RuntimeException {

    public DuplicatePersonalLoanException(Throwable cause) {
        super("The customer already has an active personal loan.", cause);
    }
}
