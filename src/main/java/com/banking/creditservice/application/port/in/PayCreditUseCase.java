package com.banking.creditservice.application.port.in;

import com.banking.creditservice.application.command.PayCreditCommand;
import com.banking.creditservice.application.model.OperationResult;
import reactor.core.publisher.Mono;

/** Command: pays debt of a loan or card, idempotent by operationId. */
public interface PayCreditUseCase {

  Mono<OperationResult> execute(PayCreditCommand command);
}
