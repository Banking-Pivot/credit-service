package com.banking.credit_service.application.port.in;

import com.banking.credit_service.application.model.ResultPage;
import com.banking.credit_service.application.query.ListCreditMovementsQuery;
import com.banking.credit_service.domain.model.Movement;
import reactor.core.publisher.Mono;

/** Query: a page of the movement history of a product. */
public interface ListCreditMovementsUseCase {

    Mono<ResultPage<Movement>> execute(ListCreditMovementsQuery query);
}
