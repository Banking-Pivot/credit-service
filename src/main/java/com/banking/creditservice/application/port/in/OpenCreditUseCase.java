package com.banking.creditservice.application.port.in;

import com.banking.creditservice.application.command.OpenCreditCommand;
import com.banking.creditservice.application.model.CreditView;
import reactor.core.publisher.Mono;

/** Command: opens a loan or a card for an active customer. */
public interface OpenCreditUseCase {

  Mono<CreditView> execute(OpenCreditCommand command);
}
