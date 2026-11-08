package com.banking.credit_service.application.port.out;

import com.banking.credit_service.application.model.ResultPage;
import com.banking.credit_service.application.query.PageRequest;
import com.banking.credit_service.domain.model.Movement;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** History of a product, newest first: occurredAt descending, then id descending. */
public interface MovementReadPort {

    Mono<ResultPage<Movement>> findPage(UUID creditId, PageRequest pageRequest);

    Flux<Movement> findLatest(UUID creditId, int limit);
}
