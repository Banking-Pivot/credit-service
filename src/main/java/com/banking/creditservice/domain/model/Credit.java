package com.banking.creditservice.domain.model;

import com.banking.creditservice.domain.exception.CreditClosedException;
import com.banking.creditservice.domain.exception.CreditRuleViolationException;
import com.banking.creditservice.domain.exception.RuleViolation;
import com.banking.creditservice.domain.model.enums.CreditStatus;
import com.banking.creditservice.domain.model.enums.CreditType;
import com.banking.creditservice.domain.model.enums.CurrencyCode;
import com.banking.creditservice.domain.model.enums.CustomerType;
import com.banking.creditservice.domain.model.enums.MovementType;
import com.banking.creditservice.domain.model.vo.CreditApplicant;
import com.banking.creditservice.domain.model.vo.CreditTerms;
import com.banking.creditservice.domain.model.vo.OpenedCredit;
import com.banking.creditservice.domain.model.vo.PostingContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.Getter;

/**
 * Credit product aggregate: a personal loan, a business loan or a credit card. It owns the
 * outstanding debt and is the only place that changes it: a loan starts owing its principal, a card
 * starts at zero, and afterwards only charges and payments move the debt. Every one of them returns
 * the {@link Movement} to record.
 *
 * <p>While it owes, the product has a due date: the next payment day after the debt appeared. A
 * payment of at least the minimum moves it to the next cycle; one that leaves no debt clears it.
 * The product is overdue when it still owes after that date.
 *
 * <p>Every change increments {@code version}, which is also the HTTP ETag. The caller loads the
 * product under a row lock, applies one change and stores the result conditioned on the version it
 * loaded.
 */
@Getter
public final class Credit {

  private final UUID id;
  private final UUID customerId;
  private final CustomerType customerType;
  private final CreditType type;
  private final CurrencyCode currency;

  /** Loans only: the amount lent. */
  private final BigDecimal principal;

  private final Instant openedAt;

  /** Cards only: the largest outstanding debt allowed. */
  private BigDecimal creditLimit;

  private BigDecimal outstandingDebt;
  private int paymentDueDay;

  /** Day the next payment is due; {@code null} while nothing is owed. */
  private LocalDate dueDate;

  private CreditStatus status;
  private Instant updatedAt;
  private Instant closedAt;
  private long version;

  private Credit(
      UUID id,
      UUID customerId,
      CustomerType customerType,
      CreditType type,
      CurrencyCode currency,
      BigDecimal principal,
      BigDecimal creditLimit,
      BigDecimal outstandingDebt,
      int paymentDueDay,
      LocalDate dueDate,
      CreditStatus status,
      Instant openedAt,
      Instant updatedAt,
      Instant closedAt,
      long version) {
    this.id = Objects.requireNonNull(id, "id");
    this.customerId = Objects.requireNonNull(customerId, "customerId");
    this.customerType = Objects.requireNonNull(customerType, "customerType");
    this.type = Objects.requireNonNull(type, "type");
    this.currency = Objects.requireNonNull(currency, "currency");
    this.principal = principal;
    this.creditLimit = creditLimit;
    this.outstandingDebt = Objects.requireNonNull(outstandingDebt, "outstandingDebt");
    this.paymentDueDay = paymentDueDay;
    this.dueDate = dueDate;
    this.status = Objects.requireNonNull(status, "status");
    this.openedAt = Objects.requireNonNull(openedAt, "openedAt");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    this.closedAt = closedAt;
    this.version = version;
  }

  /**
   * Opens an ACTIVE product for a customer Customer Service reported as existing.
   *
   * <p>The customer must be active and of a type the product accepts. A loan starts with its
   * principal as outstanding debt, a DISBURSEMENT movement under the operation of the context and
   * its first due date; a card starts with zero debt, no movement and no due date.
   *
   * @param terms already validated for {@code type} by {@link CreditTerms#forOpening}
   */
  public static OpenedCredit open(
      UUID id,
      CreditApplicant applicant,
      CreditType type,
      CurrencyCode currency,
      CreditTerms terms,
      PostingContext context) {
    if (!applicant.active()) {
      throw violation(RuleViolation.CUSTOMER_INACTIVE, "The customer is not active.");
    }
    if (!type.isAllowedFor(applicant.type())) {
      throw violation(
          RuleViolation.PRODUCT_NOT_ALLOWED,
          type + " is not available for " + applicant.type() + " customers.");
    }
    if (type.isLoan() != (terms.principal() != null)
        || type.isCard() != (terms.creditLimit() != null)) {
      throw CreditTerms.invalid("The terms do not match the product type.");
    }
    Instant now = context.occurredAt();
    BigDecimal debt = type.isLoan() ? terms.principal() : Amounts.ZERO;
    Credit credit =
        new Credit(
            id,
            applicant.customerId(),
            applicant.type(),
            type,
            currency,
            terms.principal(),
            terms.creditLimit(),
            debt,
            terms.paymentDueDay(),
            type.isLoan() ? nextDueDateAfter(context.businessDate(), terms.paymentDueDay()) : null,
            CreditStatus.ACTIVE,
            now,
            now,
            null,
            0);
    Optional<Movement> disbursement =
        type.isLoan()
            ? Optional.of(
                new Movement(
                    UUID.randomUUID(),
                    id,
                    context.operationId(),
                    MovementType.DISBURSEMENT,
                    debt,
                    currency,
                    debt,
                    null,
                    now))
            : Optional.empty();
    return new OpenedCredit(credit, disbursement);
  }

  /** Rebuilds a stored product. */
  public static Credit restore(
      UUID id,
      UUID customerId,
      CustomerType customerType,
      CreditType type,
      CurrencyCode currency,
      BigDecimal principal,
      BigDecimal creditLimit,
      BigDecimal outstandingDebt,
      int paymentDueDay,
      LocalDate dueDate,
      CreditStatus status,
      Instant openedAt,
      Instant updatedAt,
      Instant closedAt,
      long version) {
    return new Credit(
        id,
        customerId,
        customerType,
        type,
        currency,
        principal,
        creditLimit,
        outstandingDebt,
        paymentDueDay,
        dueDate,
        status,
        openedAt,
        updatedAt,
        closedAt,
        version);
  }

  /**
   * Charges a purchase to an active card that is not overdue: same currency, positive amount and
   * not above the available credit. The debt grows by the amount. A card that owed nothing, or that
   * owed without a due date, gets the next payment day after the business date as due date.
   */
  public Movement charge(BigDecimal amount, CurrencyCode requestCurrency, PostingContext context) {
    requireActive();
    if (!type.isCard()) {
      throw violation(RuleViolation.NOT_A_CREDIT_CARD, "Only credit cards accept charges.");
    }
    if (isOverdue(context.businessDate())) {
      throw violation(
          RuleViolation.CREDIT_OVERDUE,
          "The credit card has overdue debt since " + dueDate + "; pay it before charging.");
    }
    requireCurrency(requestCurrency);
    BigDecimal charged = Amounts.positive(amount);
    BigDecimal available = creditLimit.subtract(outstandingDebt);
    if (charged.compareTo(available) > 0) {
      throw violation(
          RuleViolation.INSUFFICIENT_AVAILABLE_CREDIT,
          "The charge exceeds the available credit of " + available.toPlainString() + ".");
    }
    if (dueDate == null) {
      dueDate = nextDueDateAfter(context.businessDate(), paymentDueDay);
    }
    return post(MovementType.CHARGE, charged, context);
  }

  /**
   * Pays part or all of the debt of an active loan or card: same currency, positive amount and not
   * above the outstanding debt. On a card the amount becomes available. Anybody can pay: the
   * payment does not say who pays.
   *
   * <p>A payment that leaves no debt clears the due date. One that leaves debt and reaches the
   * minimum payment (the rate times the debt before it, rounded half up to two decimals) moves the
   * due date: an overdue product, or one that owed without a due date, gets the next payment day
   * after the business date; a product paid in its current cycle gets the due date of the next
   * cycle; a product already paid for this cycle keeps its date. A smaller payment only lowers the
   * debt.
   *
   * @param minimumPaymentRate share of the debt a payment must reach to count for the cycle
   */
  public Movement pay(
      BigDecimal amount,
      CurrencyCode requestCurrency,
      PostingContext context,
      BigDecimal minimumPaymentRate) {
    requireActive();
    requireCurrency(requestCurrency);
    BigDecimal paid = Amounts.positive(amount);
    if (paid.compareTo(outstandingDebt) > 0) {
      throw violation(
          RuleViolation.PAYMENT_EXCEEDS_DEBT,
          "The payment exceeds the outstanding debt of " + outstandingDebt.toPlainString() + ".");
    }
    BigDecimal minimum =
        outstandingDebt.multiply(minimumPaymentRate).setScale(2, RoundingMode.HALF_UP);
    if (paid.compareTo(outstandingDebt) == 0) {
      dueDate = null;
    } else if (paid.compareTo(minimum) >= 0) {
      dueDate = dueDateAfterQualifyingPayment(context.businessDate());
    }
    return post(MovementType.PAYMENT, paid, context);
  }

  /**
   * Replaces the editable terms: the payment day of every product and the limit of a card. The
   * limit is required for cards, not allowed for loans and must cover the current debt. The debt
   * itself never changes here.
   *
   * @return {@code false} when the terms are the current ones and nothing changed
   */
  public boolean updateTerms(Integer newPaymentDueDay, BigDecimal newCreditLimit, Instant now) {
    requireActive();
    int day = CreditTerms.requireDueDay(newPaymentDueDay);
    BigDecimal limit = null;
    if (type.isLoan() && newCreditLimit != null) {
      throw CreditTerms.invalid("A loan does not take a credit limit.");
    }
    if (type.isCard()) {
      if (newCreditLimit == null) {
        throw CreditTerms.invalid("A credit card needs a credit limit.");
      }
      limit = Amounts.positive(newCreditLimit);
      if (limit.compareTo(outstandingDebt) < 0) {
        throw violation(
            RuleViolation.CREDIT_LIMIT_BELOW_DEBT,
            "The credit limit cannot be below the outstanding debt of "
                + outstandingDebt.toPlainString()
                + ".");
      }
    }
    if (day == paymentDueDay && Objects.equals(limit, creditLimit)) {
      return false;
    }
    paymentDueDay = day;
    creditLimit = limit;
    touch(now);
    return true;
  }

  /**
   * Closes the product logically; it keeps its history. Requires zero debt.
   *
   * @return {@code false} when the product was already closed and nothing changed
   */
  public boolean close(Instant now) {
    if (status == CreditStatus.CLOSED) {
      return false;
    }
    if (outstandingDebt.signum() != 0) {
      throw violation(
          RuleViolation.DEBT_NOT_ZERO,
          "The outstanding debt of "
              + outstandingDebt.toPlainString()
              + " must be paid before closing.");
    }
    status = CreditStatus.CLOSED;
    closedAt = now;
    touch(now);
    return true;
  }

  /** Cards only: limit minus outstanding debt. Empty for loans. */
  public Optional<BigDecimal> availableCredit() {
    return type.isCard() ? Optional.of(creditLimit.subtract(outstandingDebt)) : Optional.empty();
  }

  public boolean isActive() {
    return status == CreditStatus.ACTIVE;
  }

  /**
   * Whether the product still owes after its due date. A product without debt or without a due date
   * is never overdue.
   *
   * @param businessDate today in the business zone
   */
  public boolean isOverdue(LocalDate businessDate) {
    return outstandingDebt.signum() > 0 && dueDate != null && businessDate.isAfter(dueDate);
  }

  /**
   * The first date strictly after {@code date} whose day of the month is {@code paymentDueDay}.
   * Payment days go from 1 to 28, so every month has one.
   */
  public static LocalDate nextDueDateAfter(LocalDate date, int paymentDueDay) {
    return nextDueDateOnOrAfter(date.plusDays(1), paymentDueDay);
  }

  private static LocalDate nextDueDateOnOrAfter(LocalDate date, int dayOfMonth) {
    LocalDate candidate = date.withDayOfMonth(dayOfMonth);
    return candidate.isBefore(date) ? candidate.plusMonths(1) : candidate;
  }

  /**
   * The due date a payment of at least the minimum leaves when debt remains. The current cycle is
   * recognised by the day of the stored due date, and the new date takes the current payment day,
   * so a changed payment day only shows in the following due dates.
   */
  private LocalDate dueDateAfterQualifyingPayment(LocalDate today) {
    if (dueDate == null || today.isAfter(dueDate)) {
      return nextDueDateAfter(today, paymentDueDay);
    }
    if (dueDate.equals(nextDueDateOnOrAfter(today, dueDate.getDayOfMonth()))) {
      return nextDueDateOnOrAfter(dueDate.plusMonths(1), paymentDueDay);
    }
    return dueDate;
  }

  private Movement post(MovementType movementType, BigDecimal amount, PostingContext context) {
    outstandingDebt = outstandingDebt.add(movementType.debtChange(amount));
    touch(context.occurredAt());
    return new Movement(
        UUID.randomUUID(),
        id,
        context.operationId(),
        movementType,
        amount,
        currency,
        outstandingDebt,
        context.description(),
        context.occurredAt());
  }

  private void touch(Instant now) {
    updatedAt = now;
    version++;
  }

  private void requireActive() {
    if (!isActive()) {
      throw new CreditClosedException(id);
    }
  }

  private void requireCurrency(CurrencyCode requestCurrency) {
    if (requestCurrency != currency) {
      throw violation(
          RuleViolation.CURRENCY_MISMATCH,
          "The product uses " + currency + "; the operation must use the same currency.");
    }
  }

  private static CreditRuleViolationException violation(RuleViolation violation, String message) {
    return new CreditRuleViolationException(violation, message);
  }
}
