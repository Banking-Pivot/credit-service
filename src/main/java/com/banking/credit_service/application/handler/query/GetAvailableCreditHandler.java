package com.banking.credit_service.application.handler.query;

import com.banking.credit_service.application.exception.CreditNotFoundException;
import com.banking.credit_service.application.model.CreditBalanceView;
import com.banking.credit_service.application.port.in.GetAvailableCreditUseCase;
import com.banking.credit_service.application.port.out.CreditReadPort;
import com.banking.credit_service.application.query.GetAvailableCreditQuery;
import com.banking.credit_service.application.support.BusinessCalendar;
import com.banking.credit_service.domain.exception.CreditRuleViolationException;
import com.banking.credit_service.domain.exception.RuleViolation;
import reactor.core.publisher.Mono;

import java.util.Objects;

/** Available credit of a card: limit minus outstanding debt. A loan has none (422). */
public class GetAvailableCreditHandler implements GetAvailableCreditUseCase {

    private final CreditReadPort credits;
    private final BusinessCalendar calendar;

    public GetAvailableCreditHandler(CreditReadPort credits, BusinessCalendar calendar) {
        this.credits = Objects.requireNonNull(credits);
        this.calendar = Objects.requireNonNull(calendar);
    }

    @Override
    public Mono<CreditBalanceView> execute(GetAvailableCreditQuery query) {
        return credits.findById(query.creditId())
                .switchIfEmpty(Mono.error(() -> new CreditNotFoundException(query.creditId())))
                .map(credit -> {
                    if (!credit.isCard()) {
                        throw new CreditRuleViolationException(RuleViolation.NOT_A_CREDIT_CARD,
                                "Only credit cards have available credit.");
                    }
                    return new CreditBalanceView(credit, calendar.now());
                });
    }
}
