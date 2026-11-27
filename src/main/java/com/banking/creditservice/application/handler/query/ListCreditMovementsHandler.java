package com.banking.creditservice.application.handler.query;

import com.banking.creditservice.application.exception.CreditNotFoundException;
import com.banking.creditservice.application.model.ResultPage;
import com.banking.creditservice.application.port.in.ListCreditMovementsUseCase;
import com.banking.creditservice.application.port.out.CreditReadPort;
import com.banking.creditservice.application.port.out.MovementReadPort;
import com.banking.creditservice.application.query.ListCreditMovementsQuery;
import com.banking.creditservice.domain.model.Movement;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** Movement history of a product, newest first. A missing product is 404, not an empty page. */
public class ListCreditMovementsHandler implements ListCreditMovementsUseCase {

  private final CreditReadPort credits;
  private final MovementReadPort movements;

  public ListCreditMovementsHandler(CreditReadPort credits, MovementReadPort movements) {
    this.credits = Objects.requireNonNull(credits);
    this.movements = Objects.requireNonNull(movements);
  }

  @Override
  public Mono<ResultPage<Movement>> execute(ListCreditMovementsQuery query) {
    return credits
        .existsById(query.creditId())
        .flatMap(
            exists ->
                Boolean.TRUE.equals(exists)
                    ? movements.findPage(query.creditId(), query.pageRequest())
                    : Mono.error(new CreditNotFoundException(query.creditId())));
  }
}
