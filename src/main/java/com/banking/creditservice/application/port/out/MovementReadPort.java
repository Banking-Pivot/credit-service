package com.banking.creditservice.application.port.out;

import com.banking.creditservice.application.model.ResultPage;
import com.banking.creditservice.application.query.PageRequest;
import com.banking.creditservice.domain.model.Movement;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** History of a product, newest first: occurredAt descending, then id descending. */
public interface MovementReadPort {

  Mono<ResultPage<Movement>> findPage(UUID creditId, PageRequest pageRequest);

  Flux<Movement> findLatest(UUID creditId, int limit);
}
