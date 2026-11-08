package com.banking.credit_service.application.handler.query;

import com.banking.credit_service.application.exception.CreditNotFoundException;
import com.banking.credit_service.application.model.CreditBalanceView;
import com.banking.credit_service.application.port.in.GetCreditDebtUseCase;
import com.banking.credit_service.application.port.out.CreditReadPort;
import com.banking.credit_service.application.query.GetCreditDebtQuery;
import com.banking.credit_service.application.support.BusinessCalendar;
import reactor.core.publisher.Mono;

import java.util.Objects;

/** Outstanding debt of a loan or card, ACTIVE or CLOSED. */
public class GetCreditDebtHandler implements GetCreditDebtUseCase {

    private final CreditReadPort credits;
    private final BusinessCalendar calendar;

    public GetCreditDebtHandler(CreditReadPort credits, BusinessCalendar calendar) {
        this.credits = Objects.requireNonNull(credits);
        this.calendar = Objects.requireNonNull(calendar);
    }

    @Override
    public Mono<CreditBalanceView> execute(GetCreditDebtQuery query) {
        return credits.findById(query.creditId())
                .switchIfEmpty(Mono.error(() -> new CreditNotFoundException(query.creditId())))
                .map(credit -> new CreditBalanceView(credit, calendar.now()));
    }
}
