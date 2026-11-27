package com.banking.creditservice.application.command;

import com.banking.creditservice.domain.model.enums.CreditType;
import com.banking.creditservice.domain.model.enums.CurrencyCode;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Opens a credit product.
 *
 * @param principal loans only
 * @param creditLimit cards only
 */
public record OpenCreditCommand(
    UUID customerId,
    CreditType type,
    CurrencyCode currency,
    BigDecimal principal,
    BigDecimal creditLimit,
    Integer paymentDueDay) {

  /**
   * Requires the customer, the product type and the currency. Principal, limit and payment day are
   * validated against the type when the product is opened.
   */
  public OpenCreditCommand {
    Objects.requireNonNull(customerId, "customerId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(currency, "currency");
  }
}
