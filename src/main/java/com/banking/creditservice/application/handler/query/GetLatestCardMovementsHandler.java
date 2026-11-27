package com.banking.creditservice.application.handler.query;

import com.banking.creditservice.application.exception.CreditNotFoundException;
import com.banking.creditservice.application.port.in.GetLatestCardMovementsUseCase;
import com.banking.creditservice.application.port.out.CreditReadPort;
import com.banking.creditservice.application.port.out.MovementReadPort;
import com.banking.creditservice.application.query.GetLatestCardMovementsQuery;
import com.banking.creditservice.domain.exception.CreditRuleViolationException;
import com.banking.creditservice.domain.exception.RuleViolation;
import com.banking.creditservice.domain.model.Movement;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** The last ten movements of a credit card, newest first. A loan answers 422. */
public class GetLatestCardMovementsHandler implements GetLatestCardMovementsUseCase {

  private final CreditReadPort credits;
  private final MovementReadPort movements;

  public GetLatestCardMovementsHandler(CreditReadPort credits, MovementReadPort movements) {
    this.credits = Objects.requireNonNull(credits);
    this.movements = Objects.requireNonNull(movements);
  }

  @Override
  public Mono<List<Movement>> execute(GetLatestCardMovementsQuery query) {
    return credits
        .findById(query.creditId())
        .switchIfEmpty(Mono.error(() -> new CreditNotFoundException(query.creditId())))
        .flatMap(
            credit ->
                credit.isCard()
                    ? movements
                        .findLatest(query.creditId(), GetLatestCardMovementsQuery.LIMIT)
                        .collectList()
                    : Mono.error(
                        new CreditRuleViolationException(
                            RuleViolation.NOT_A_CREDIT_CARD,
                            "Only credit cards have this view; use the movement history.")));
  }
}
