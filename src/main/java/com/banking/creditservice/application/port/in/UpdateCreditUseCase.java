package com.banking.creditservice.application.port.in;

import com.banking.creditservice.application.command.UpdateCreditCommand;
import com.banking.creditservice.application.model.CreditView;
import reactor.core.publisher.Mono;

/** Command: replaces the editable terms of an active product. */
public interface UpdateCreditUseCase {

  Mono<CreditView> execute(UpdateCreditCommand command);
}
