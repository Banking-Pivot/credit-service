package com.banking.creditservice.application.port.in;

import com.banking.creditservice.application.query.GetLatestCardMovementsQuery;
import com.banking.creditservice.domain.model.Movement;
import java.util.List;
import reactor.core.publisher.Mono;

/** Query: the last ten movements of a credit card. */
public interface GetLatestCardMovementsUseCase {

  Mono<List<Movement>> execute(GetLatestCardMovementsQuery query);
}
