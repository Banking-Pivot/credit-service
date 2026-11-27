package com.banking.creditservice.application.handler.query;

import com.banking.creditservice.application.exception.CreditNotFoundException;
import com.banking.creditservice.application.model.CreditBalanceView;
import com.banking.creditservice.application.port.in.GetCreditDebtUseCase;
import com.banking.creditservice.application.port.out.CreditReadPort;
import com.banking.creditservice.application.query.GetCreditDebtQuery;
import com.banking.creditservice.application.support.BusinessCalendar;
import java.util.Objects;
import reactor.core.publisher.Mono;

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
    return credits
        .findById(query.creditId())
        .switchIfEmpty(Mono.error(() -> new CreditNotFoundException(query.creditId())))
        .map(credit -> new CreditBalanceView(credit, calendar.now()));
  }
}
