package com.banking.credit_service.domain.model;

import com.banking.credit_service.domain.exception.CreditRuleViolationException;
import com.banking.credit_service.domain.exception.RuleViolation;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money rules shared by the domain: two decimals, never rounded silently. Amounts match the
 * NUMERIC(14, 2) columns of the database. The REST contract already rejects more than two
 * decimals with 400; this is the guard of the domain itself.
 */
public final class Amounts {

    /** Decimal places of every amount. */
    public static final int SCALE = 2;

    /** Largest principal, limit or operation amount: twelve integer digits. */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");

    /** Zero with the money scale. */
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);

    private Amounts() {
    }

    /** Validates a strictly positive amount and returns it with scale 2. */
    public static BigDecimal positive(BigDecimal amount) {
        if (amount == null) {
            throw invalid("The amount is required.");
        }
        if (amount.signum() <= 0) {
            throw invalid("The amount must be greater than zero.");
        }
        BigDecimal normalized;
        try {
            normalized = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw invalid("The amount can have at most two decimals.");
        }
        if (normalized.compareTo(MAX_AMOUNT) > 0) {
            throw invalid("The amount exceeds " + MAX_AMOUNT.toPlainString() + ".");
        }
        return normalized;
    }

    private static CreditRuleViolationException invalid(String message) {
        return new CreditRuleViolationException(RuleViolation.INVALID_AMOUNT, message);
    }
}
