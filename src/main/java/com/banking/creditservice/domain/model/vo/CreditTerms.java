package com.banking.creditservice.domain.model.vo;

import com.banking.creditservice.domain.exception.CreditRuleViolationException;
import com.banking.creditservice.domain.exception.RuleViolation;
import com.banking.creditservice.domain.model.Amounts;
import com.banking.creditservice.domain.model.enums.CreditType;
import java.math.BigDecimal;

/**
 * Amounts and payment day a product is opened with. A loan has a principal and no limit; a card has
 * a limit and no principal. Checked before any external call.
 *
 * @param principal loans only, scale 2
 * @param creditLimit cards only, scale 2
 * @param paymentDueDay day of the month the payment is due, 1 to 28
 */
public record CreditTerms(BigDecimal principal, BigDecimal creditLimit, int paymentDueDay) {

  public static final int FIRST_DUE_DAY = 1;
  public static final int LAST_DUE_DAY = 28;

  /** Validates the terms a request sends for the product type. */
  public static CreditTerms forOpening(
      CreditType type, BigDecimal principal, BigDecimal creditLimit, Integer paymentDueDay) {
    int day = requireDueDay(paymentDueDay);
    if (type.isLoan()) {
      if (principal == null) {
        throw invalid("A loan needs a principal.");
      }
      if (creditLimit != null) {
        throw invalid("A loan does not take a credit limit.");
      }
      return new CreditTerms(Amounts.positive(principal), null, day);
    }
    if (creditLimit == null) {
      throw invalid("A credit card needs a credit limit.");
    }
    if (principal != null) {
      throw invalid("A credit card does not take a principal.");
    }
    return new CreditTerms(null, Amounts.positive(creditLimit), day);
  }

  /** Returns the payment due day when it is a valid day of the month, or refuses it. */
  public static int requireDueDay(Integer paymentDueDay) {
    if (paymentDueDay == null || paymentDueDay < FIRST_DUE_DAY || paymentDueDay > LAST_DUE_DAY) {
      throw invalid(
          "paymentDueDay must be between " + FIRST_DUE_DAY + " and " + LAST_DUE_DAY + ".");
    }
    return paymentDueDay;
  }

  /** The error for terms that break a rule of the credit. */
  public static CreditRuleViolationException invalid(String message) {
    return new CreditRuleViolationException(RuleViolation.INVALID_PRODUCT_TERMS, message);
  }
}
