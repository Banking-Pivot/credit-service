package com.banking.creditservice.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banking.creditservice.domain.exception.CreditClosedException;
import com.banking.creditservice.domain.exception.CreditRuleViolationException;
import com.banking.creditservice.domain.exception.RuleViolation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Rules of the credit aggregate, without Spring or a database. */
class CreditTest {

  private static final Instant NOW = Instant.parse("2026-11-16T15:00:00Z");
  private static final Instant LATER = NOW.plusSeconds(60);
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
  private static final BigDecimal RATE = new BigDecimal("0.05");

  private static final CreditApplicant PERSON =
      new CreditApplicant(UUID.randomUUID(), CustomerType.PERSONAL, true);
  private static final CreditApplicant COMPANY =
      new CreditApplicant(UUID.randomUUID(), CustomerType.BUSINESS, true);

  @Nested
  class Opening {

    @Test
    void cardStartsWithoutDebtAndWithoutMovement() {
      OpenedCredit opened = open(PERSON, CreditType.CREDIT_CARD, null, "1000.00");

      Credit card = opened.credit();
      assertThat(opened.disbursement()).isEmpty();
      assertMoney(card.getOutstandingDebt(), "0.00");
      assertMoney(card.getCreditLimit(), "1000.00");
      assertThat(card.getPrincipal()).isNull();
      assertMoney(card.availableCredit().orElseThrow(), "1000.00");
      assertThat(card.getStatus()).isEqualTo(CreditStatus.ACTIVE);
      assertThat(card.getVersion()).isZero();
      assertThat(card.getOpenedAt()).isEqualTo(NOW);
    }

    @Test
    void loanStartsOwingItsPrincipalWithADisbursement() {
      OpenedCredit opened = open(PERSON, CreditType.PERSONAL_LOAN, "5000.00", null);

      Credit loan = opened.credit();
      assertMoney(loan.getOutstandingDebt(), "5000.00");
      assertThat(loan.availableCredit()).isEmpty();
      Movement disbursement = opened.disbursement().orElseThrow();
      assertThat(disbursement.type()).isEqualTo(MovementType.DISBURSEMENT);
      assertMoney(disbursement.amount(), "5000.00");
      assertMoney(disbursement.debtAfter(), "5000.00");
      assertThat(disbursement.creditId()).isEqualTo(loan.getId());
      assertThat(disbursement.occurredAt()).isEqualTo(NOW);
    }

    @Test
    void inactiveCustomerCannotOpen() {
      CreditApplicant inactive =
          new CreditApplicant(UUID.randomUUID(), CustomerType.PERSONAL, false);

      assertViolation(
          () -> open(inactive, CreditType.CREDIT_CARD, null, "100.00"),
          RuleViolation.CUSTOMER_INACTIVE);
    }

    @Test
    void loansFollowTheCustomerType() {
      assertViolation(
          () -> open(COMPANY, CreditType.PERSONAL_LOAN, "100.00", null),
          RuleViolation.PRODUCT_NOT_ALLOWED);
      assertViolation(
          () -> open(PERSON, CreditType.BUSINESS_LOAN, "100.00", null),
          RuleViolation.PRODUCT_NOT_ALLOWED);
      assertThat(open(COMPANY, CreditType.BUSINESS_LOAN, "100.00", null).credit().getType())
          .isEqualTo(CreditType.BUSINESS_LOAN);
      assertThat(open(COMPANY, CreditType.CREDIT_CARD, null, "100.00").credit().getCustomerType())
          .isEqualTo(CustomerType.BUSINESS);
    }

    @Test
    void termsMustMatchTheProduct() {
      assertViolation(
          () -> terms(CreditType.PERSONAL_LOAN, null, null, 15),
          RuleViolation.INVALID_PRODUCT_TERMS);
      assertViolation(
          () -> terms(CreditType.PERSONAL_LOAN, "100", "100", 15),
          RuleViolation.INVALID_PRODUCT_TERMS);
      assertViolation(
          () -> terms(CreditType.CREDIT_CARD, null, null, 15), RuleViolation.INVALID_PRODUCT_TERMS);
      assertViolation(
          () -> terms(CreditType.CREDIT_CARD, "100", "100", 15),
          RuleViolation.INVALID_PRODUCT_TERMS);
      assertViolation(
          () -> terms(CreditType.CREDIT_CARD, null, "100", null),
          RuleViolation.INVALID_PRODUCT_TERMS);
      assertViolation(
          () -> terms(CreditType.CREDIT_CARD, null, "100", 29),
          RuleViolation.INVALID_PRODUCT_TERMS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1.00", "10.001", "1000000000000.00"})
    void amountsArePositiveWithTwoDecimalsAtMost(String amount) {
      assertViolation(
          () -> terms(CreditType.PERSONAL_LOAN, amount, null, 15), RuleViolation.INVALID_AMOUNT);
      assertViolation(
          () -> terms(CreditType.CREDIT_CARD, null, amount, 15), RuleViolation.INVALID_AMOUNT);
    }

    @Test
    void amountsKeepTwoDecimals() {
      assertThat(terms(CreditType.CREDIT_CARD, null, "1000", 1).creditLimit())
          .isEqualTo(new BigDecimal("1000.00"));
      assertThat(terms(CreditType.PERSONAL_LOAN, "999999999999.99", null, 28).principal())
          .isEqualTo(new BigDecimal("999999999999.99"));
    }
  }

  @Nested
  class Charges {

    @Test
    void chargeRaisesTheDebtAndRecordsIt() {
      Credit card = card("1000.00");

      Movement movement =
          card.charge(new BigDecimal("300.00"), CurrencyCode.PEN, context("Supermarket"));

      assertMoney(card.getOutstandingDebt(), "300.00");
      assertMoney(card.availableCredit().orElseThrow(), "700.00");
      assertThat(card.getVersion()).isEqualTo(1);
      assertThat(card.getUpdatedAt()).isEqualTo(LATER);
      assertThat(movement.type()).isEqualTo(MovementType.CHARGE);
      assertMoney(movement.amount(), "300.00");
      assertMoney(movement.debtAfter(), "300.00");
      assertThat(movement.description()).isEqualTo("Supermarket");
    }

    @Test
    void chargeCanUseExactlyTheAvailableCredit() {
      Credit card = card("1000.00");
      card.charge(new BigDecimal("600.00"), CurrencyCode.PEN, context(null));

      card.charge(new BigDecimal("400.00"), CurrencyCode.PEN, context(null));

      assertMoney(card.availableCredit().orElseThrow(), "0.00");
      assertViolation(
          () -> card.charge(new BigDecimal("0.01"), CurrencyCode.PEN, context(null)),
          RuleViolation.INSUFFICIENT_AVAILABLE_CREDIT);
      assertMoney(card.getOutstandingDebt(), "1000.00");
    }

    @Test
    void chargeIsRejectedWithoutChangingTheCard() {
      Credit card = card("1000.00");

      assertViolation(
          () -> card.charge(new BigDecimal("1000.01"), CurrencyCode.PEN, context(null)),
          RuleViolation.INSUFFICIENT_AVAILABLE_CREDIT);
      assertViolation(
          () -> card.charge(new BigDecimal("10.00"), CurrencyCode.USD, context(null)),
          RuleViolation.CURRENCY_MISMATCH);
      assertViolation(
          () -> card.charge(new BigDecimal("0.00"), CurrencyCode.PEN, context(null)),
          RuleViolation.INVALID_AMOUNT);
      assertViolation(
          () -> card.charge(new BigDecimal("1.005"), CurrencyCode.PEN, context(null)),
          RuleViolation.INVALID_AMOUNT);

      assertMoney(card.getOutstandingDebt(), "0.00");
      assertThat(card.getVersion()).isZero();
    }

    @Test
    void onlyCardsAcceptCharges() {
      Credit loan = loan("5000.00");

      assertViolation(
          () -> loan.charge(new BigDecimal("10.00"), CurrencyCode.PEN, context(null)),
          RuleViolation.NOT_A_CREDIT_CARD);
    }
  }

  @Nested
  class Payments {

    @Test
    void paymentLowersTheDebtAndFreesCardCredit() {
      Credit card = card("1000.00");
      card.charge(new BigDecimal("300.00"), CurrencyCode.PEN, context(null));

      Movement movement = card.pay(new BigDecimal("100.00"), CurrencyCode.PEN, context(null), RATE);

      assertMoney(card.getOutstandingDebt(), "200.00");
      assertMoney(card.availableCredit().orElseThrow(), "800.00");
      assertThat(movement.type()).isEqualTo(MovementType.PAYMENT);
      assertMoney(movement.debtAfter(), "200.00");
    }

    @Test
    void loanCanBePaidInFullButNotBeyond() {
      Credit loan = loan("500.00");

      assertViolation(
          () -> loan.pay(new BigDecimal("500.01"), CurrencyCode.PEN, context(null), RATE),
          RuleViolation.PAYMENT_EXCEEDS_DEBT);
      loan.pay(new BigDecimal("500.00"), CurrencyCode.PEN, context(null), RATE);

      assertMoney(loan.getOutstandingDebt(), "0.00");
      assertViolation(
          () -> loan.pay(new BigDecimal("0.01"), CurrencyCode.PEN, context(null), RATE),
          RuleViolation.PAYMENT_EXCEEDS_DEBT);
    }

    @Test
    void paymentNeedsTheProductCurrency() {
      Credit loan = loan("500.00");

      assertViolation(
          () -> loan.pay(new BigDecimal("10.00"), CurrencyCode.USD, context(null), RATE),
          RuleViolation.CURRENCY_MISMATCH);
    }
  }

  @Nested
  class TermsAndClosing {

    @Test
    void limitCannotGoBelowTheDebt() {
      Credit card = card("1000.00");
      card.charge(new BigDecimal("400.00"), CurrencyCode.PEN, context(null));

      assertViolation(
          () -> card.updateTerms(10, new BigDecimal("399.99"), LATER),
          RuleViolation.CREDIT_LIMIT_BELOW_DEBT);
      assertThat(card.updateTerms(10, new BigDecimal("400.00"), LATER)).isTrue();

      assertMoney(card.getCreditLimit(), "400.00");
      assertMoney(card.getOutstandingDebt(), "400.00");
      assertThat(card.getPaymentDueDay()).isEqualTo(10);
    }

    @Test
    void currentTermsChangeNothing() {
      Credit card = card("1000.00");

      assertThat(card.updateTerms(15, new BigDecimal("1000"), LATER)).isFalse();

      assertThat(card.getVersion()).isZero();
      assertThat(card.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void limitBelongsToCardsOnly() {
      assertViolation(
          () -> loan("100.00").updateTerms(10, new BigDecimal("100.00"), LATER),
          RuleViolation.INVALID_PRODUCT_TERMS);
      assertViolation(
          () -> card("100.00").updateTerms(10, null, LATER), RuleViolation.INVALID_PRODUCT_TERMS);
      assertThat(loan("100.00").updateTerms(1, null, LATER)).isTrue();
    }

    @Test
    void closingNeedsZeroDebtAndKeepsTheProduct() {
      Credit loan = loan("100.00");

      assertViolation(() -> loan.close(LATER), RuleViolation.DEBT_NOT_ZERO);
      loan.pay(new BigDecimal("100.00"), CurrencyCode.PEN, context(null), RATE);
      assertThat(loan.close(LATER)).isTrue();

      assertThat(loan.getStatus()).isEqualTo(CreditStatus.CLOSED);
      assertThat(loan.getClosedAt()).isEqualTo(LATER);
      assertThat(loan.getVersion()).isEqualTo(2);
      assertThat(loan.close(LATER.plusSeconds(1))).as("already closed").isFalse();
      assertThat(loan.getVersion()).isEqualTo(2);
    }

    @Test
    void closedProductRejectsEveryChange() {
      Credit card = card("100.00");
      card.close(LATER);

      assertThatThrownBy(() -> card.charge(new BigDecimal("1.00"), CurrencyCode.PEN, context(null)))
          .isInstanceOf(CreditClosedException.class);
      assertThatThrownBy(
              () -> card.pay(new BigDecimal("1.00"), CurrencyCode.PEN, context(null), RATE))
          .isInstanceOf(CreditClosedException.class);
      assertThatThrownBy(() -> card.updateTerms(10, new BigDecimal("100.00"), LATER))
          .isInstanceOf(CreditClosedException.class);
    }
  }

  @Nested
  class DueDates {

    private static final LocalDate DECEMBER_15 = LocalDate.of(2026, 12, 15);
    private static final LocalDate JANUARY_15 = LocalDate.of(2027, 1, 15);

    @Test
    void nextDueDateIsStrictlyAfterTheDate() {
      assertThat(Credit.nextDueDateAfter(LocalDate.of(2026, 11, 14), 15))
          .isEqualTo(LocalDate.of(2026, 11, 15));
      assertThat(Credit.nextDueDateAfter(LocalDate.of(2026, 11, 15), 15)).isEqualTo(DECEMBER_15);
      assertThat(Credit.nextDueDateAfter(LocalDate.of(2026, 11, 16), 15)).isEqualTo(DECEMBER_15);
      assertThat(Credit.nextDueDateAfter(LocalDate.of(2026, 12, 31), 1))
          .isEqualTo(LocalDate.of(2027, 1, 1));
      assertThat(Credit.nextDueDateAfter(LocalDate.of(2027, 1, 31), 28))
          .isEqualTo(LocalDate.of(2027, 2, 28));
    }

    @Test
    void loanIsDueFromItsDisbursementAndCardFromItsFirstCharge() {
      Credit loan = loan("1000.00");
      Credit card = card("1000.00");

      assertThat(loan.getDueDate()).isEqualTo(DECEMBER_15);
      assertThat(card.getDueDate()).isNull();

      card.charge(new BigDecimal("100.00"), CurrencyCode.PEN, on(TODAY.plusDays(3)));
      assertThat(card.getDueDate()).isEqualTo(DECEMBER_15);
      card.charge(new BigDecimal("100.00"), CurrencyCode.PEN, on(TODAY.plusDays(20)));
      assertThat(card.getDueDate()).as("a later charge keeps the date").isEqualTo(DECEMBER_15);
    }

    @Test
    void overdueStartsTheDayAfterTheDueDateAndNeedsDebt() {
      Credit loan = loan("1000.00");
      Credit card = card("1000.00");

      assertThat(loan.isOverdue(DECEMBER_15)).isFalse();
      assertThat(loan.isOverdue(DECEMBER_15.plusDays(1))).isTrue();
      assertThat(card.isOverdue(DECEMBER_15.plusYears(1))).as("nothing owed").isFalse();
      assertThat(withoutDueDate("300.00").isOverdue(DECEMBER_15.plusYears(1)))
          .as("debt from before due dates existed")
          .isFalse();
    }

    @Test
    void payingEverythingClearsTheDueDate() {
      Credit loan = loan("1000.00");

      loan.pay(new BigDecimal("1000.00"), CurrencyCode.PEN, on(DECEMBER_15.plusDays(9)), RATE);

      assertThat(loan.getDueDate()).isNull();
      assertThat(loan.isOverdue(DECEMBER_15.plusDays(9))).isFalse();
    }

    @Test
    void onTimeMinimumPaymentMovesTheDueDateOnceInACycle() {
      Credit loan = loan("1000.00");

      loan.pay(new BigDecimal("50.00"), CurrencyCode.PEN, on(TODAY), RATE);
      assertThat(loan.getDueDate()).isEqualTo(JANUARY_15);

      loan.pay(new BigDecimal("500.00"), CurrencyCode.PEN, on(TODAY.plusDays(1)), RATE);
      assertThat(loan.getDueDate()).as("second payment of the cycle").isEqualTo(JANUARY_15);

      loan.pay(new BigDecimal("22.50"), CurrencyCode.PEN, on(DECEMBER_15), RATE);
      assertThat(loan.getDueDate()).as("still before the new cycle").isEqualTo(JANUARY_15);

      loan.pay(new BigDecimal("21.38"), CurrencyCode.PEN, on(DECEMBER_15.plusDays(1)), RATE);
      assertThat(loan.getDueDate()).isEqualTo(LocalDate.of(2027, 2, 15));
    }

    @Test
    void paymentOnTheDueDateStillCountsForItsCycle() {
      Credit loan = loan("1000.00");

      loan.pay(new BigDecimal("50.00"), CurrencyCode.PEN, on(DECEMBER_15), RATE);

      assertThat(loan.getDueDate()).isEqualTo(JANUARY_15);
    }

    @Test
    void paymentBelowTheMinimumKeepsTheDueDate() {
      Credit loan = loan("1000.00");

      loan.pay(new BigDecimal("49.99"), CurrencyCode.PEN, on(TODAY), RATE);
      assertThat(loan.getDueDate()).isEqualTo(DECEMBER_15);
      assertMoney(loan.getOutstandingDebt(), "950.01");

      loan.pay(new BigDecimal("47.49"), CurrencyCode.PEN, on(DECEMBER_15.plusDays(5)), RATE);
      assertThat(loan.getDueDate()).isEqualTo(DECEMBER_15);
      assertThat(loan.isOverdue(DECEMBER_15.plusDays(5))).isTrue();
    }

    @Test
    void minimumPaymentIsRoundedHalfUpToTwoDecimals() {
      // 5 % of 100.10 is 5.005: the minimum is 5.01.
      Credit short1 = withDebt("100.10");
      short1.pay(new BigDecimal("5.00"), CurrencyCode.PEN, on(TODAY), RATE);
      assertThat(short1.getDueDate()).isEqualTo(DECEMBER_15);

      Credit exact = withDebt("100.10");
      exact.pay(new BigDecimal("5.01"), CurrencyCode.PEN, on(TODAY), RATE);
      assertThat(exact.getDueDate()).isEqualTo(JANUARY_15);
    }

    @Test
    void minimumPaymentOfAnOverdueProductSetsTheNextDueDateAfterToday() {
      Credit loan = loan("1000.00");
      LocalDate late = LocalDate.of(2027, 2, 20);

      loan.pay(new BigDecimal("50.00"), CurrencyCode.PEN, on(late), RATE);

      assertThat(loan.getDueDate()).isEqualTo(LocalDate.of(2027, 3, 15));
      assertThat(loan.isOverdue(late)).isFalse();
    }

    @Test
    void debtWithoutDueDateGetsOneWithAChargeOrAMinimumPayment() {
      LocalDate day = LocalDate.of(2027, 5, 15);
      Credit charged = withoutDueDate("300.00");
      charged.charge(new BigDecimal("10.00"), CurrencyCode.PEN, on(day));
      assertThat(charged.getDueDate()).isEqualTo(LocalDate.of(2027, 6, 15));

      Credit paid = withoutDueDate("300.00");
      paid.pay(new BigDecimal("14.99"), CurrencyCode.PEN, on(day), RATE);
      assertThat(paid.getDueDate()).as("below the minimum").isNull();
      paid.pay(new BigDecimal("15.00"), CurrencyCode.PEN, on(day), RATE);
      assertThat(paid.getDueDate()).isEqualTo(LocalDate.of(2027, 6, 15));
    }

    @Test
    void newPaymentDayShowsInTheNextDueDateOnly() {
      Credit later = loan("1000.00");
      later.updateTerms(20, null, LATER);
      assertThat(later.getDueDate()).isEqualTo(DECEMBER_15);
      later.pay(new BigDecimal("50.00"), CurrencyCode.PEN, on(TODAY), RATE);
      assertThat(later.getDueDate()).isEqualTo(LocalDate.of(2027, 1, 20));
      later.pay(new BigDecimal("50.00"), CurrencyCode.PEN, on(TODAY.plusDays(1)), RATE);
      assertThat(later.getDueDate()).as("once per cycle").isEqualTo(LocalDate.of(2027, 1, 20));

      Credit earlier = loan("1000.00");
      earlier.updateTerms(5, null, LATER);
      earlier.pay(new BigDecimal("50.00"), CurrencyCode.PEN, on(TODAY), RATE);
      assertThat(earlier.getDueDate())
          .as("never less than a month after the date it replaces")
          .isEqualTo(LocalDate.of(2027, 2, 5));
    }

    @Test
    void overdueCardRejectsChargesUntilAMinimumPayment() {
      Credit card = withDebt("100.00");
      LocalDate late = DECEMBER_15.plusDays(1);

      assertViolation(
          () -> card.charge(new BigDecimal("10.00"), CurrencyCode.PEN, on(late)),
          RuleViolation.CREDIT_OVERDUE);
      assertMoney(card.getOutstandingDebt(), "100.00");

      card.pay(new BigDecimal("5.00"), CurrencyCode.PEN, on(late), RATE);
      card.charge(new BigDecimal("10.00"), CurrencyCode.PEN, on(late));

      assertMoney(card.getOutstandingDebt(), "105.00");
      assertThat(card.getDueDate()).isEqualTo(JANUARY_15);
    }

    @Test
    void closedCardIsReportedAsClosedBeforeOverdue() {
      Credit card = card("100.00");
      card.close(LATER);

      assertThatThrownBy(
              () -> card.charge(new BigDecimal("1.00"), CurrencyCode.PEN, on(DECEMBER_15)))
          .isInstanceOf(CreditClosedException.class);
    }

    @Test
    void postingContextNeedsTheBusinessDate() {
      UUID operationId = UUID.randomUUID();

      assertThatThrownBy(() -> new PostingContext(operationId, NOW, null, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("businessDate");
      assertThat(on(DECEMBER_15).businessDate()).isEqualTo(DECEMBER_15);
    }

    /** A card charged on the opening day: it owes the amount and is due on 15 December. */
    private static Credit withDebt(String debt) {
      Credit card = card("1000.00");
      card.charge(new BigDecimal(debt), CurrencyCode.PEN, on(TODAY));
      return card;
    }

    /** A card stored with debt before due dates existed. */
    private static Credit withoutDueDate(String debt) {
      return Credit.restore(
          UUID.randomUUID(),
          PERSON.customerId(),
          CustomerType.PERSONAL,
          CreditType.CREDIT_CARD,
          CurrencyCode.PEN,
          null,
          new BigDecimal("1000.00"),
          new BigDecimal(debt),
          15,
          null,
          CreditStatus.ACTIVE,
          NOW,
          NOW,
          null,
          3);
    }

    private static PostingContext on(LocalDate businessDate) {
      return new PostingContext(UUID.randomUUID(), LATER, businessDate, null);
    }
  }

  private static OpenedCredit open(
      CreditApplicant applicant, CreditType type, String principal, String limit) {
    CreditTerms terms = terms(type, principal, limit, 15);
    return Credit.open(
        UUID.randomUUID(),
        applicant,
        type,
        CurrencyCode.PEN,
        terms,
        new PostingContext(UUID.randomUUID(), NOW, TODAY, null));
  }

  private static CreditTerms terms(CreditType type, String principal, String limit, Integer day) {
    return CreditTerms.forOpening(
        type,
        principal == null ? null : new BigDecimal(principal),
        limit == null ? null : new BigDecimal(limit),
        day);
  }

  private static Credit card(String limit) {
    return open(PERSON, CreditType.CREDIT_CARD, null, limit).credit();
  }

  private static Credit loan(String principal) {
    return open(PERSON, CreditType.PERSONAL_LOAN, principal, null).credit();
  }

  private static PostingContext context(String description) {
    return new PostingContext(UUID.randomUUID(), LATER, TODAY, description);
  }

  private static void assertViolation(ThrowingCallable call, RuleViolation violation) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            CreditRuleViolationException.class,
            exception -> assertThat(exception.violation()).isEqualTo(violation));
  }

  private static void assertMoney(BigDecimal actual, String expected) {
    assertThat(actual).isEqualByComparingTo(expected);
  }
}
