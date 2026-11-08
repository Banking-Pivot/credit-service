package com.banking.credit_service.application.port.out;

import com.banking.credit_service.domain.model.Movement;
import reactor.core.publisher.Mono;

/** Appends to the history of a product, inside the transaction that changed its debt. */
public interface MovementWritePort {

    Mono<Void> append(Movement movement);
}
