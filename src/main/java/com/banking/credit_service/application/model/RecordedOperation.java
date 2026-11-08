package com.banking.credit_service.application.model;

import com.banking.credit_service.domain.model.CurrencyCode;
import com.banking.credit_service.domain.model.Movement;
import com.banking.credit_service.domain.model.OperationType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A completed charge or payment as stored under its operationId: the request data used to
 * recognize repeats and the result returned to the client, also on every repeat.
 *
 * @param debtAfter           outstanding debt right after the operation
 * @param availableCreditAfter cards only, {@code null} for loans
 */
public record RecordedOperation(
        UUID operationId,
        OperationType type,
        UUID creditId,
        BigDecimal amount,
        CurrencyCode currency,
        String description,
        UUID movementId,
        BigDecimal debtAfter,
        BigDecimal availableCreditAfter,
        Instant occurredAt
) {

    /** The record a new operation leaves once it commits. */
    public static RecordedOperation of(OperationRequest request, Movement movement, BigDecimal availableCreditAfter) {
        return new RecordedOperation(request.operationId(), request.type(), request.creditId(), request.amount(),
                request.currency(), request.description(), movement.id(), movement.debtAfter(), availableCreditAfter,
                movement.occurredAt());
    }
}
