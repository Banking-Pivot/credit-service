package com.banking.creditservice.application.port.in;

import com.banking.creditservice.application.command.ChargeCreditCommand;
import com.banking.creditservice.application.model.OperationResult;
import reactor.core.publisher.Mono;

/** Command: charges a purchase to a card, idempotent by operationId. */
public interface ChargeCreditUseCase {

  Mono<OperationResult> execute(ChargeCreditCommand command);
}
