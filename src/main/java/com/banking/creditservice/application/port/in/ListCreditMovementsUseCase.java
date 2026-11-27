package com.banking.creditservice.application.port.in;

import com.banking.creditservice.application.model.ResultPage;
import com.banking.creditservice.application.query.ListCreditMovementsQuery;
import com.banking.creditservice.domain.model.Movement;
import reactor.core.publisher.Mono;

/** Query: a page of the movement history of a product. */
public interface ListCreditMovementsUseCase {

  Mono<ResultPage<Movement>> execute(ListCreditMovementsQuery query);
}
