package com.banking.credit_service.application.model;

import java.util.Objects;

/**
 * What a charge or payment returns.
 *
 * @param replayed {@code true} when the request repeated a recorded operation and the debt
 *                 did not change again; the operation is then the original one
 */
public record OperationResult(RecordedOperation operation, boolean replayed) {

    public OperationResult {
        Objects.requireNonNull(operation, "operation");
    }
}
