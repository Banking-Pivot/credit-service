package com.banking.credit_service.application.port.in;

import com.banking.credit_service.application.query.GetLatestCardMovementsQuery;
import com.banking.credit_service.domain.model.Movement;
import reactor.core.publisher.Mono;

import java.util.List;

/** Query: the last ten movements of a credit card. */
public interface GetLatestCardMovementsUseCase {

    Mono<List<Movement>> execute(GetLatestCardMovementsQuery query);
}
