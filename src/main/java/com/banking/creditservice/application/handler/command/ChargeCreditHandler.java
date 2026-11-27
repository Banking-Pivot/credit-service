package com.banking.creditservice.application.handler.command;

import com.banking.creditservice.application.command.ChargeCreditCommand;
import com.banking.creditservice.application.model.OperationResult;
import com.banking.creditservice.application.port.in.ChargeCreditUseCase;
import com.banking.creditservice.domain.model.Credit;
import java.util.Objects;
import reactor.core.publisher.Mono;

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
