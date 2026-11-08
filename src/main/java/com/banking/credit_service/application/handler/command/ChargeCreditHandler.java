package com.banking.credit_service.application.handler.command;

import com.banking.credit_service.application.command.ChargeCreditCommand;
import com.banking.credit_service.application.model.OperationResult;
import com.banking.credit_service.application.port.in.ChargeCreditUseCase;
import com.banking.credit_service.domain.model.Credit;
import reactor.core.publisher.Mono;

import java.util.Objects;

/** Charges a purchase to a credit card. Uses only the state kept by Credit Service. */
public class ChargeCreditHandler implements ChargeCreditUseCase {

    private final CreditOperationExecutor executor;

    public ChargeCreditHandler(CreditOperationExecutor executor) {
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public Mono<OperationResult> execute(ChargeCreditCommand command) {
        return Mono.fromCallable(command::toRequest)
                .flatMap(request -> executor.execute(request, Credit::charge));
    }
}
