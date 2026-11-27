package com.banking.creditservice.application.model;

import com.banking.creditservice.domain.model.Amounts;
import com.banking.creditservice.domain.model.enums.CurrencyCode;
import com.banking.creditservice.domain.model.enums.OperationType;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * The data an operationId stands for. A repeated request is recognized when the product, operation
 * type, amount and currency match the recorded operation. The description is not compared: a repeat
 * answers with the original one.
 *
 * @param creditId product in the request path
 * @param amount scale 2
 * @param description stripped; blank becomes {@code null}
 */
public record OperationRequest(
    UUID operationId,
    OperationType type,
    UUID creditId,
    BigDecimal amount,
    CurrencyCode currency,
    String description) {

  /**
   * Requires the operationId, type, product and currency. The amount must be greater than zero and
   * is kept with scale 2; a blank description becomes {@code null}.
   */
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
