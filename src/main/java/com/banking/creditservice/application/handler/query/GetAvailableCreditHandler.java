package com.banking.creditservice.application.handler.query;

import com.banking.creditservice.application.exception.CreditNotFoundException;
import com.banking.creditservice.application.model.CreditBalanceView;
import com.banking.creditservice.application.port.in.GetAvailableCreditUseCase;
import com.banking.creditservice.application.port.out.CreditReadPort;
import com.banking.creditservice.application.query.GetAvailableCreditQuery;
import com.banking.creditservice.application.support.BusinessCalendar;
import com.banking.creditservice.domain.exception.CreditRuleViolationException;
import com.banking.creditservice.domain.exception.RuleViolation;
import java.util.Objects;
import reactor.core.publisher.Mono;

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
    return credits
        .findById(query.creditId())
        .switchIfEmpty(Mono.error(() -> new CreditNotFoundException(query.creditId())))
        .map(
            credit -> {
              if (!credit.isCard()) {
                throw new CreditRuleViolationException(
                    RuleViolation.NOT_A_CREDIT_CARD, "Only credit cards have available credit.");
              }
              return new CreditBalanceView(credit, calendar.now());
            });
  }
}
