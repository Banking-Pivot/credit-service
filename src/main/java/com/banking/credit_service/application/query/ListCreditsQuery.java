package com.banking.credit_service.application.query;

import com.banking.credit_service.domain.model.CreditStatus;
import com.banking.credit_service.domain.model.CreditType;

import java.util.Objects;
import java.util.UUID;

/**
 * Products matching every filter that is present. Missing filters match everything.
 * Answered from this database only; the customer is not looked up.
 */
public record ListCreditsQuery(PageRequest pageRequest, UUID customerId, CreditType type, CreditStatus status) {

    public ListCreditsQuery {
        Objects.requireNonNull(pageRequest, "pageRequest");
    }
}
