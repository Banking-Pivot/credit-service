package com.banking.credit_service.application.exception;

import java.util.UUID;

/** The operationId already identifies an operation with a different product, type, amount or currency. */
public class OperationIdConflictException extends RuntimeException {

    public OperationIdConflictException(UUID operationId) {
        super("Operation " + operationId + " was already used with different data.");
    }
}
