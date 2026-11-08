package com.banking.credit_service.application.port.in;

import com.banking.credit_service.application.model.CreditBalanceView;
import com.banking.credit_service.application.query.GetAvailableCreditQuery;
import reactor.core.publisher.Mono;

/** Query: available credit of a card. */
public interface GetAvailableCreditUseCase {

    Mono<CreditBalanceView> execute(GetAvailableCreditQuery query);
}
