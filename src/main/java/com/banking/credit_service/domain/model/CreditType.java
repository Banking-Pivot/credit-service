package com.banking.credit_service.domain.model;

/**
 * Credit products and the customers that can hold them.
 *
 * <ul>
 *   <li>PERSONAL_LOAN: personal customers, at most one ACTIVE per customer.</li>
 *   <li>BUSINESS_LOAN: business customers, any number.</li>
 *   <li>CREDIT_CARD: personal and business customers, any number.</li>
 * </ul>
 */
public enum CreditType {
    PERSONAL_LOAN,
    BUSINESS_LOAN,
    CREDIT_CARD;

    public boolean isCard() {
        return this == CREDIT_CARD;
    }

    public boolean isLoan() {
        return !isCard();
    }

    public boolean isAllowedFor(CustomerType customerType) {
        return switch (this) {
            case PERSONAL_LOAN -> customerType == CustomerType.PERSONAL;
            case BUSINESS_LOAN -> customerType == CustomerType.BUSINESS;
            case CREDIT_CARD -> true;
        };
    }
}
