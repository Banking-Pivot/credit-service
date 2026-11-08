package com.banking.credit_service.application.model;

import com.banking.credit_service.domain.model.Amounts;
import com.banking.credit_service.domain.model.CurrencyCode;
import com.banking.credit_service.domain.model.OperationType;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * The data an operationId stands for. A repeated request is recognized when the product,
 * operation type, amount and currency match the recorded operation. The description is
 * not compared: a repeat answers with the original one.
 *
 * @param creditId    product in the request path
 * @param amount      scale 2
 * @param description stripped; blank becomes {@code null}
 */
public record OperationRequest(
        UUID operationId,
        OperationType type,
        UUID creditId,
        BigDecimal amount,
        CurrencyCode currency,
        String description
) {

    public OperationRequest {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(creditId, "creditId");
        Objects.requireNonNull(currency, "currency");
        amount = Amounts.positive(amount);
        description = description == null || description.isBlank() ? null : description.strip();
    }

    /** Whether the recorded operation was created by a request with this data. */
    public boolean matches(RecordedOperation recorded) {
        return type == recorded.type()
                && creditId.equals(recorded.creditId())
                && amount.compareTo(recorded.amount()) == 0
                && currency == recorded.currency();
    }
}
