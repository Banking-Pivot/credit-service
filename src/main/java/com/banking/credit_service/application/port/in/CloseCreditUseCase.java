package com.banking.credit_service.application.port.in;

import com.banking.credit_service.application.command.CloseCreditCommand;
import reactor.core.publisher.Mono;

/** Command: closes a product without debt, keeping its history. */
public interface CloseCreditUseCase {

    Mono<Void> execute(CloseCreditCommand command);
}
