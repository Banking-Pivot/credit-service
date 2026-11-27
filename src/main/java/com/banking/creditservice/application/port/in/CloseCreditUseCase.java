package com.banking.creditservice.application.port.in;

import com.banking.creditservice.application.command.CloseCreditCommand;
import reactor.core.publisher.Mono;

/** Command: closes a product without debt, keeping its history. */
public interface CloseCreditUseCase {

  Mono<Void> execute(CloseCreditCommand command);
}
