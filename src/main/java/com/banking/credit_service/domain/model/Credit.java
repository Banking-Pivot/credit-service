package com.banking.credit_service.domain.model;

import com.banking.credit_service.domain.exception.CreditClosedException;
import com.banking.credit_service.domain.exception.CreditRuleViolationException;
import com.banking.credit_service.domain.exception.RuleViolation;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Credit product aggregate: a personal loan, a business loan or a credit card. It owns the
 * outstanding debt and is the only place that changes it: a loan starts owing its
 * principal, a card starts at zero, and afterwards only charges and payments move the
 * debt. Every one of them returns the {@link Movement} to record.
 *
 * <p>Every change increments {@code version}, which is also the HTTP ETag. The caller
 * loads the product under a row lock, applies one change and stores the result
 * conditioned on the version it loaded.
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
            CreditStatus status,
            Instant openedAt,
            Instant updatedAt,
            Instant closedAt,
            long version
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.customerType = Objects.requireNonNull(customerType, "customerType");
        this.type = Objects.requireNonNull(type, "type");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.principal = principal;
        this.creditLimit = creditLimit;
        this.outstandingDebt = Objects.requireNonNull(outstandingDebt, "outstandingDebt");
        this.paymentDueDay = paymentDueDay;
        this.status = Objects.requireNonNull(status, "status");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.closedAt = closedAt;
        this.version = version;
    }

    /**
     * Opens an ACTIVE product for a customer Customer Service reported as existing.
     *
     * <p>The customer must be active and of a type the product accepts. A loan starts with
     * its principal as outstanding debt and a DISBURSEMENT movement under the operation of
     * the context; a card starts with zero debt and no movement.
     *
     * @param terms already validated for {@code type} by {@link CreditTerms#forOpening}
     */
    public static OpenedCredit open(
            UUID id,
            CreditApplicant applicant,
            CreditType type,
            CurrencyCode currency,
            CreditTerms terms,
            PostingContext context
    ) {
        if (!applicant.active()) {
            throw violation(RuleViolation.CUSTOMER_INACTIVE, "The customer is not active.");
        }
        if (!type.isAllowedFor(applicant.type())) {
            throw violation(RuleViolation.PRODUCT_NOT_ALLOWED,
                    type + " is not available for " + applicant.type() + " customers.");
        }
        if (type.isLoan() != (terms.principal() != null) || type.isCard() != (terms.creditLimit() != null)) {
            throw CreditTerms.invalid("The terms do not match the product type.");
        }
        Instant now = context.occurredAt();
        BigDecimal debt = type.isLoan() ? terms.principal() : Amounts.ZERO;
        Credit credit = new Credit(id, applicant.customerId(), applicant.type(), type, currency, terms.principal(),
                terms.creditLimit(), debt, terms.paymentDueDay(), CreditStatus.ACTIVE, now, now, null, 0);
        Optional<Movement> disbursement = type.isLoan()
                ? Optional.of(new Movement(UUID.randomUUID(), id, context.operationId(), MovementType.DISBURSEMENT,
                debt, currency, debt, null, now))
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
            CreditStatus status,
            Instant openedAt,
            Instant updatedAt,
            Instant closedAt,
            long version
    ) {
        return new Credit(id, customerId, customerType, type, currency, principal, creditLimit, outstandingDebt,
                paymentDueDay, status, openedAt, updatedAt, closedAt, version);
    }

    /**
     * Charges a purchase to an active card: same currency, positive amount and not above
     * the available credit. The debt grows by the amount.
     */
    public Movement charge(BigDecimal amount, CurrencyCode requestCurrency, PostingContext context) {
        requireActive();
        if (!type.isCard()) {
            throw violation(RuleViolation.NOT_A_CREDIT_CARD, "Only credit cards accept charges.");
        }
        requireCurrency(requestCurrency);
        BigDecimal charged = Amounts.positive(amount);
        BigDecimal available = creditLimit.subtract(outstandingDebt);
        if (charged.compareTo(available) > 0) {
            throw violation(RuleViolation.INSUFFICIENT_AVAILABLE_CREDIT,
                    "The charge exceeds the available credit of " + available.toPlainString() + ".");
        }
        return post(MovementType.CHARGE, charged, context);
    }

    /**
     * Pays part or all of the debt of an active loan or card: same currency, positive
     * amount and not above the outstanding debt. On a card the amount becomes available.
     */
    public Movement pay(BigDecimal amount, CurrencyCode requestCurrency, PostingContext context) {
        requireActive();
        requireCurrency(requestCurrency);
        BigDecimal paid = Amounts.positive(amount);
        if (paid.compareTo(outstandingDebt) > 0) {
            throw violation(RuleViolation.PAYMENT_EXCEEDS_DEBT,
                    "The payment exceeds the outstanding debt of " + outstandingDebt.toPlainString() + ".");
        }
        return post(MovementType.PAYMENT, paid, context);
    }

    /**
     * Replaces the editable terms: the payment day of every product and the limit of a
     * card. The limit is required for cards, not allowed for loans and must cover the
     * current debt. The debt itself never changes here.
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
                throw violation(RuleViolation.CREDIT_LIMIT_BELOW_DEBT,
                        "The credit limit cannot be below the outstanding debt of "
                                + outstandingDebt.toPlainString() + ".");
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
            throw violation(RuleViolation.DEBT_NOT_ZERO,
                    "The outstanding debt of " + outstandingDebt.toPlainString() + " must be paid before closing.");
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

    private Movement post(MovementType movementType, BigDecimal amount, PostingContext context) {
        outstandingDebt = outstandingDebt.add(movementType.debtChange(amount));
        touch(context.occurredAt());
        return new Movement(UUID.randomUUID(), id, context.operationId(), movementType, amount, currency,
                outstandingDebt, context.description(), context.occurredAt());
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
            throw violation(RuleViolation.CURRENCY_MISMATCH,
                    "The product uses " + currency + "; the operation must use the same currency.");
        }
    }

    private static CreditRuleViolationException violation(RuleViolation violation, String message) {
        return new CreditRuleViolationException(violation, message);
    }
}
