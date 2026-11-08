package com.banking.credit_service.application.port.in;

import com.banking.credit_service.application.command.OpenCreditCommand;
import com.banking.credit_service.application.model.CreditView;
import reactor.core.publisher.Mono;

/** Command: opens a loan or a card for an active customer. */
public interface OpenCreditUseCase {

    Mono<CreditView> execute(OpenCreditCommand command);
}
