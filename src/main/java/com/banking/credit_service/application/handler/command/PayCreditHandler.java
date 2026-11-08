package com.banking.credit_service.application.handler.command;

import com.banking.credit_service.application.command.PayCreditCommand;
import com.banking.credit_service.application.model.OperationResult;
import com.banking.credit_service.application.port.in.PayCreditUseCase;
import com.banking.credit_service.domain.model.Credit;
import reactor.core.publisher.Mono;

import java.util.Objects;

/** Pays debt of a loan or card. Uses only the state kept by Credit Service. */
public class PayCreditHandler implements PayCreditUseCase {

    private final CreditOperationExecutor executor;

    public PayCreditHandler(CreditOperationExecutor executor) {
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public Mono<OperationResult> execute(PayCreditCommand command) {
        return Mono.fromCallable(command::toRequest)
                .flatMap(request -> executor.execute(request, Credit::pay));
    }
}
