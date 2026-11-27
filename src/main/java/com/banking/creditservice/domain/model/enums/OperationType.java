package com.banking.creditservice.domain.model.enums;

/** Monetary operations a client requests with an operationId. */
public enum OperationType {
  CHARGE,
  PAYMENT;

  public MovementType movementType() {
    return this == CHARGE ? MovementType.CHARGE : MovementType.PAYMENT;
  }
}
