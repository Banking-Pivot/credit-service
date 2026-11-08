package com.banking.credit_service.application.port.in;

import com.banking.credit_service.application.command.PayCreditCommand;
import com.banking.credit_service.application.model.OperationResult;
import reactor.core.publisher.Mono;

/** Command: pays debt of a loan or card, idempotent by operationId. */
public interface PayCreditUseCase {

    Mono<OperationResult> execute(PayCreditCommand command);
}
