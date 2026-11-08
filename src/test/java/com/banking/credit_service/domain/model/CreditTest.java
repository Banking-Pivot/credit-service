package com.banking.credit_service.domain.model;

import com.banking.credit_service.domain.exception.CreditClosedException;
import com.banking.credit_service.domain.exception.CreditRuleViolationException;
import com.banking.credit_service.domain.exception.RuleViolation;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Rules of the credit aggregate, without Spring or a database. */
class CreditTest {

    private static final Instant NOW = Instant.parse("2026-11-16T15:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(60);

    private static final CreditApplicant PERSON = new CreditApplicant(UUID.randomUUID(), CustomerType.PERSONAL, true);
    private static final CreditApplicant COMPANY = new CreditApplicant(UUID.randomUUID(), CustomerType.BUSINESS, true);

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
            CreditApplicant inactive = new CreditApplicant(UUID.randomUUID(), CustomerType.PERSONAL, false);

            assertViolation(() -> open(inactive, CreditType.CREDIT_CARD, null, "100.00"), RuleViolation.CUSTOMER_INACTIVE);
        }

        @Test
        void loansFollowTheCustomerType() {
            assertViolation(() -> open(COMPANY, CreditType.PERSONAL_LOAN, "100.00", null), RuleViolation.PRODUCT_NOT_ALLOWED);
            assertViolation(() -> open(PERSON, CreditType.BUSINESS_LOAN, "100.00", null), RuleViolation.PRODUCT_NOT_ALLOWED);
            assertThat(open(COMPANY, CreditType.BUSINESS_LOAN, "100.00", null).credit().getType())
                    .isEqualTo(CreditType.BUSINESS_LOAN);
            assertThat(open(COMPANY, CreditType.CREDIT_CARD, null, "100.00").credit().getCustomerType())
                    .isEqualTo(CustomerType.BUSINESS);
        }

        @Test
        void termsMustMatchTheProduct() {
            assertViolation(() -> terms(CreditType.PERSONAL_LOAN, null, null, 15), RuleViolation.INVALID_PRODUCT_TERMS);
            assertViolation(() -> terms(CreditType.PERSONAL_LOAN, "100", "100", 15), RuleViolation.INVALID_PRODUCT_TERMS);
            assertViolation(() -> terms(CreditType.CREDIT_CARD, null, null, 15), RuleViolation.INVALID_PRODUCT_TERMS);
            assertViolation(() -> terms(CreditType.CREDIT_CARD, "100", "100", 15), RuleViolation.INVALID_PRODUCT_TERMS);
            assertViolation(() -> terms(CreditType.CREDIT_CARD, null, "100", null), RuleViolation.INVALID_PRODUCT_TERMS);
            assertViolation(() -> terms(CreditType.CREDIT_CARD, null, "100", 29), RuleViolation.INVALID_PRODUCT_TERMS);
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-1.00", "10.001", "1000000000000.00"})
        void amountsArePositiveWithTwoDecimalsAtMost(String amount) {
            assertViolation(() -> terms(CreditType.PERSONAL_LOAN, amount, null, 15), RuleViolation.INVALID_AMOUNT);
            assertViolation(() -> terms(CreditType.CREDIT_CARD, null, amount, 15), RuleViolation.INVALID_AMOUNT);
        }

        @Test
        void amountsKeepTwoDecimals() {
            assertThat(terms(CreditType.CREDIT_CARD, null, "1000", 1).creditLimit()).isEqualTo(new BigDecimal("1000.00"));
            assertThat(terms(CreditType.PERSONAL_LOAN, "999999999999.99", null, 28).principal())
                    .isEqualTo(new BigDecimal("999999999999.99"));
        }
    }

    @Nested
    class Charges {

        @Test
        void chargeRaisesTheDebtAndRecordsIt() {
            Credit card = card("1000.00");

            Movement movement = card.charge(new BigDecimal("300.00"), CurrencyCode.PEN, context("Supermarket"));

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
            assertViolation(() -> card.charge(new BigDecimal("0.01"), CurrencyCode.PEN, context(null)),
                    RuleViolation.INSUFFICIENT_AVAILABLE_CREDIT);
            assertMoney(card.getOutstandingDebt(), "1000.00");
        }

        @Test
        void chargeIsRejectedWithoutChangingTheCard() {
            Credit card = card("1000.00");

            assertViolation(() -> card.charge(new BigDecimal("1000.01"), CurrencyCode.PEN, context(null)),
                    RuleViolation.INSUFFICIENT_AVAILABLE_CREDIT);
            assertViolation(() -> card.charge(new BigDecimal("10.00"), CurrencyCode.USD, context(null)),
                    RuleViolation.CURRENCY_MISMATCH);
            assertViolation(() -> card.charge(new BigDecimal("0.00"), CurrencyCode.PEN, context(null)),
                    RuleViolation.INVALID_AMOUNT);
            assertViolation(() -> card.charge(new BigDecimal("1.005"), CurrencyCode.PEN, context(null)),
                    RuleViolation.INVALID_AMOUNT);

            assertMoney(card.getOutstandingDebt(), "0.00");
            assertThat(card.getVersion()).isZero();
        }

        @Test
        void onlyCardsAcceptCharges() {
            Credit loan = loan("5000.00");

            assertViolation(() -> loan.charge(new BigDecimal("10.00"), CurrencyCode.PEN, context(null)),
                    RuleViolation.NOT_A_CREDIT_CARD);
        }
    }

    @Nested
    class Payments {

        @Test
        void paymentLowersTheDebtAndFreesCardCredit() {
            Credit card = card("1000.00");
            card.charge(new BigDecimal("300.00"), CurrencyCode.PEN, context(null));

            Movement movement = card.pay(new BigDecimal("100.00"), CurrencyCode.PEN, context(null));

            assertMoney(card.getOutstandingDebt(), "200.00");
            assertMoney(card.availableCredit().orElseThrow(), "800.00");
            assertThat(movement.type()).isEqualTo(MovementType.PAYMENT);
            assertMoney(movement.debtAfter(), "200.00");
        }

        @Test
        void loanCanBePaidInFullButNotBeyond() {
            Credit loan = loan("500.00");

            assertViolation(() -> loan.pay(new BigDecimal("500.01"), CurrencyCode.PEN, context(null)),
                    RuleViolation.PAYMENT_EXCEEDS_DEBT);
            loan.pay(new BigDecimal("500.00"), CurrencyCode.PEN, context(null));

            assertMoney(loan.getOutstandingDebt(), "0.00");
            assertViolation(() -> loan.pay(new BigDecimal("0.01"), CurrencyCode.PEN, context(null)),
                    RuleViolation.PAYMENT_EXCEEDS_DEBT);
        }

        @Test
        void paymentNeedsTheProductCurrency() {
            Credit loan = loan("500.00");

            assertViolation(() -> loan.pay(new BigDecimal("10.00"), CurrencyCode.USD, context(null)),
                    RuleViolation.CURRENCY_MISMATCH);
        }
    }

    @Nested
    class TermsAndClosing {

        @Test
        void limitCannotGoBelowTheDebt() {
            Credit card = card("1000.00");
            card.charge(new BigDecimal("400.00"), CurrencyCode.PEN, context(null));

            assertViolation(() -> card.updateTerms(10, new BigDecimal("399.99"), LATER),
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
            assertViolation(() -> loan("100.00").updateTerms(10, new BigDecimal("100.00"), LATER),
                    RuleViolation.INVALID_PRODUCT_TERMS);
            assertViolation(() -> card("100.00").updateTerms(10, null, LATER), RuleViolation.INVALID_PRODUCT_TERMS);
            assertThat(loan("100.00").updateTerms(1, null, LATER)).isTrue();
        }

        @Test
        void closingNeedsZeroDebtAndKeepsTheProduct() {
            Credit loan = loan("100.00");

            assertViolation(() -> loan.close(LATER), RuleViolation.DEBT_NOT_ZERO);
            loan.pay(new BigDecimal("100.00"), CurrencyCode.PEN, context(null));
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
            assertThatThrownBy(() -> card.pay(new BigDecimal("1.00"), CurrencyCode.PEN, context(null)))
                    .isInstanceOf(CreditClosedException.class);
            assertThatThrownBy(() -> card.updateTerms(10, new BigDecimal("100.00"), LATER))
                    .isInstanceOf(CreditClosedException.class);
        }
    }

    private static OpenedCredit open(CreditApplicant applicant, CreditType type, String principal, String limit) {
        CreditTerms terms = terms(type, principal, limit, 15);
        return Credit.open(UUID.randomUUID(), applicant, type, CurrencyCode.PEN, terms,
                new PostingContext(UUID.randomUUID(), NOW, null));
    }

    private static CreditTerms terms(CreditType type, String principal, String limit, Integer day) {
        return CreditTerms.forOpening(type, principal == null ? null : new BigDecimal(principal),
                limit == null ? null : new BigDecimal(limit), day);
    }

    private static Credit card(String limit) {
        return open(PERSON, CreditType.CREDIT_CARD, null, limit).credit();
    }

    private static Credit loan(String principal) {
        return open(PERSON, CreditType.PERSONAL_LOAN, principal, null).credit();
    }

    private static PostingContext context(String description) {
        return new PostingContext(UUID.randomUUID(), LATER, description);
    }

    private static void assertViolation(ThrowingCallable call, RuleViolation violation) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(CreditRuleViolationException.class,
                        exception -> assertThat(exception.violation()).isEqualTo(violation));
    }

    private static void assertMoney(BigDecimal actual, String expected) {
        assertThat(actual).isEqualByComparingTo(expected);
    }
}
