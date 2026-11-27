package com.banking.creditservice.domain.model;

/** A product is ACTIVE until it is closed; closing is logical and final. */
public enum CreditStatus {
  ACTIVE,
  CLOSED
}
