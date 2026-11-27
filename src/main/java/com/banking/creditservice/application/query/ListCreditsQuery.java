package com.banking.creditservice.application.query;

import com.banking.creditservice.domain.model.CreditStatus;
import com.banking.creditservice.domain.model.CreditType;
import java.util.Objects;
import java.util.UUID;

/**
 * Products matching every filter that is present. Missing filters match everything. Answered from
 * this database only; the customer is not looked up.
 *
 * @param overdue {@code true} keeps the products with debt after their due date, {@code false} the
 *     others, {@code null} all of them
 */
public record ListCreditsQuery(
    PageRequest pageRequest,
    UUID customerId,
    CreditType type,
    CreditStatus status,
    Boolean overdue) {

  public ListCreditsQuery {
    Objects.requireNonNull(pageRequest, "pageRequest");
  }
}
