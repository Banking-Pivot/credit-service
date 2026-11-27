package com.banking.creditservice.application.port.in;

import com.banking.creditservice.application.model.CreditBalanceView;
import com.banking.creditservice.application.query.GetAvailableCreditQuery;
import reactor.core.publisher.Mono;

/** Query: available credit of a card. */
public interface GetAvailableCreditUseCase {

  Mono<CreditBalanceView> execute(GetAvailableCreditQuery query);
}
