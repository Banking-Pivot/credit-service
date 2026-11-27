package com.banking.creditservice.infrastructure.adapter.out.postgres;

import static org.springframework.data.relational.core.query.Criteria.where;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.data.relational.core.query.Criteria;

/**
 * The definition of overdue of the domain ({@code Credit#isOverdue}) as a filter on {@code
 * credits}: debt above zero and a due date before today. Rows without a due date are never overdue.
 */
final class OverdueCriteria {

  private static final String DEBT = "outstandingDebt";
  private static final String DUE_DATE = "dueDate";

  private OverdueCriteria() {}

  /** Products that still owe after their due date. */
  static Criteria overdue(LocalDate today) {
    return where(DEBT).greaterThan(BigDecimal.ZERO).and(DUE_DATE).lessThan(today);
  }

  /** Every other product: nothing owed, no due date, or a due date not passed yet. */
  static Criteria notOverdue(LocalDate today) {
    return where(DEBT)
        .lessThanOrEquals(BigDecimal.ZERO)
        .or(DUE_DATE)
        .isNull()
        .or(DUE_DATE)
        .greaterThanOrEquals(today);
  }
}
