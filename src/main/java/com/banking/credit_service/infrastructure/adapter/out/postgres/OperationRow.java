package com.banking.credit_service.infrastructure.adapter.out.postgres;

import com.banking.credit_service.application.model.OperationRequest;
import com.banking.credit_service.application.model.RecordedOperation;
import com.banking.credit_service.domain.model.CurrencyCode;
import com.banking.credit_service.domain.model.OperationType;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Row of {@code credit_operations}. The result columns are empty while the operation is
 * PROCESSING, a state only its own transaction can see.
 */
@Table("credit_operations")
public record OperationRow(
        @Id UUID operationId,
        OperationType operationType,
        UUID creditId,
        BigDecimal amount,
        CurrencyCode currency,
        String description,
        String status,
        UUID movementId,
        BigDecimal debtAfter,
        BigDecimal availableCreditAfter,
        Instant occurredAt
) {

    static final String PROCESSING = "PROCESSING";
    static final String COMPLETED = "COMPLETED";

    /** The claim of a key: request data only. */
    static OperationRow claimed(OperationRequest request) {
        return new OperationRow(request.operationId(), request.type(), request.creditId(), request.amount(),
                request.currency(), request.description(), PROCESSING, null, null, null, null);
    }

    RecordedOperation toRecorded() {
        return new RecordedOperation(operationId, operationType, creditId, amount, currency, description, movementId,
                debtAfter, availableCreditAfter, occurredAt);
    }
}
