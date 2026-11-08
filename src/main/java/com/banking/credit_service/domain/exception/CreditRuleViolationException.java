package com.banking.credit_service.domain.exception;

/**
 * A business rule rejected the request. Messages name rules, fields and stored values
 * only, never free text supplied by the client.
 */
public class CreditRuleViolationException extends RuntimeException {

    private final RuleViolation violation;

    public CreditRuleViolationException(RuleViolation violation, String message) {
        super(message);
        this.violation = violation;
    }

    public RuleViolation violation() {
        return violation;
    }
}
