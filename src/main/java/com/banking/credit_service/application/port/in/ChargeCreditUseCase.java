package com.banking.credit_service.application.port.in;

import com.banking.credit_service.application.command.ChargeCreditCommand;
import com.banking.credit_service.application.model.OperationResult;
import reactor.core.publisher.Mono;

/** Command: charges a purchase to a card, idempotent by operationId. */
public interface ChargeCreditUseCase {

    Mono<OperationResult> execute(ChargeCreditCommand command);
}
