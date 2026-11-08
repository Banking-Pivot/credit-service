package com.banking.credit_service.application.port.in;

import com.banking.credit_service.application.command.UpdateCreditCommand;
import com.banking.credit_service.application.model.CreditView;
import reactor.core.publisher.Mono;

/** Command: replaces the editable terms of an active product. */
public interface UpdateCreditUseCase {

    Mono<CreditView> execute(UpdateCreditCommand command);
}
