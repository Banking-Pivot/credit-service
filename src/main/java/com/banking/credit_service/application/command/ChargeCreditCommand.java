package com.banking.credit_service.application.command;

import com.banking.credit_service.application.model.OperationRequest;
import com.banking.credit_service.domain.model.CurrencyCode;
import com.banking.credit_service.domain.model.OperationType;

import java.math.BigDecimal;
import java.util.UUID;

/** Charges a purchase to a credit card, once per operationId. */
public record ChargeCreditCommand(
        UUID operationId,
        UUID creditId,
        BigDecimal amount,
        CurrencyCode currency,
        String description
) {

    public OperationRequest toRequest() {
        return new OperationRequest(operationId, OperationType.CHARGE, creditId, amount, currency, description);
    }
}
