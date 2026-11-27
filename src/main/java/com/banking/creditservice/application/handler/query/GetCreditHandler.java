package com.banking.creditservice.application.handler.query;

import com.banking.creditservice.application.exception.CreditNotFoundException;
import com.banking.creditservice.application.model.CreditView;
import com.banking.creditservice.application.port.in.GetCreditUseCase;
import com.banking.creditservice.application.port.out.CreditReadPort;
import com.banking.creditservice.application.query.GetCreditQuery;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** One product by ID. */
public class GetCreditHandler implements GetCreditUseCase {

  private final CreditReadPort credits;

  public GetCreditHandler(CreditReadPort credits) {
    this.credits = Objects.requireNonNull(credits);
  }

  @Override
  public Mono<CreditView> execute(GetCreditQuery query) {
    return credits
        .findById(query.creditId())
        .switchIfEmpty(Mono.error(() -> new CreditNotFoundException(query.creditId())));
  }
}
