package com.banking.creditservice.application.command;

import com.banking.creditservice.application.model.OperationRequest;
import com.banking.creditservice.domain.model.enums.CurrencyCode;
import com.banking.creditservice.domain.model.enums.OperationType;
import java.math.BigDecimal;
import java.util.UUID;

/** Charges a purchase to a credit card, once per operationId. */
public record ChargeCreditCommand(
    UUID operationId, UUID creditId, BigDecimal amount, CurrencyCode currency, String description) {

  public OperationRequest toRequest() {
    return new OperationRequest(
        operationId, OperationType.CHARGE, creditId, amount, currency, description);
  }
}
