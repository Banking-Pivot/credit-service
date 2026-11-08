package com.banking.credit_service.infrastructure.adapter.out.postgres;

import com.banking.credit_service.domain.model.CurrencyCode;
import com.banking.credit_service.domain.model.Movement;
import com.banking.credit_service.domain.model.MovementType;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Row of {@code credit_movements}, the append-only history. */
@Table("credit_movements")
public record MovementRow(
        @Id UUID id,
        UUID creditId,
        UUID operationId,
        MovementType movementType,
        BigDecimal amount,
        CurrencyCode currency,
        BigDecimal debtAfter,
        String description,
        Instant occurredAt
) {

    static MovementRow from(Movement movement) {
        return new MovementRow(movement.id(), movement.creditId(), movement.operationId(), movement.type(),
                movement.amount(), movement.currency(), movement.debtAfter(), movement.description(),
                movement.occurredAt());
    }

    Movement toDomain() {
        return new Movement(id, creditId, operationId, movementType, amount, currency, debtAfter, description,
                occurredAt);
    }
}
