package com.banking.creditservice.application.model;

import com.banking.creditservice.domain.model.Credit;
import com.banking.creditservice.domain.model.enums.CreditStatus;
import com.banking.creditservice.domain.model.enums.CreditType;
import com.banking.creditservice.domain.model.enums.CurrencyCode;
import com.banking.creditservice.domain.model.enums.CustomerType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read model of a credit product.
 *
 * @param principal loans only, {@code null} for cards
 * @param creditLimit cards only, {@code null} for loans
 * @param availableCredit cards only, {@code null} for loans
 * @param dueDate day the next payment is due, {@code null} while nothing is owed
 * @param overdue whether debt remained after the due date on the day the product was read
 * @param closedAt {@code null} while ACTIVE
 * @param version revision exposed as ETag
 */
public record CreditView(
    UUID id,
    UUID customerId,
    CustomerType customerType,
    CreditType type,
    CurrencyCode currency,
    BigDecimal principal,
    BigDecimal creditLimit,
    BigDecimal outstandingDebt,
    BigDecimal availableCredit,
    int paymentDueDay,
    LocalDate dueDate,
    boolean overdue,
    CreditStatus status,
    Instant openedAt,
    Instant updatedAt,
    Instant closedAt,
    long version) {

  /**
   * Copies the current state of the product. Available credit is filled in for cards and is {@code
   * null} for loans.
   *
   * @param businessDate today in the business zone, the day overdue is decided for
   */
  public static CreditView from(Credit credit, LocalDate businessDate) {
    return new CreditView(
        credit.getId(),
        credit.getCustomerId(),
        credit.getCustomerType(),
        credit.getType(),
        credit.getCurrency(),
        credit.getPrincipal(),
        credit.getCreditLimit(),
        credit.getOutstandingDebt(),
        credit.availableCredit().orElse(null),
        credit.getPaymentDueDay(),
        credit.getDueDate(),
        credit.isOverdue(businessDate),
        credit.getStatus(),
        credit.getOpenedAt(),
        credit.getUpdatedAt(),
        credit.getClosedAt(),
        credit.getVersion());
  }

  public boolean isCard() {
    return type.isCard();
  }
}
