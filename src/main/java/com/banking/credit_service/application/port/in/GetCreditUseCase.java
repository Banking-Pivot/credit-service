package com.banking.credit_service.application.port.in;

import com.banking.credit_service.application.model.CreditView;
import com.banking.credit_service.application.query.GetCreditQuery;
import reactor.core.publisher.Mono;

/** Query: one product by ID. */
public interface GetCreditUseCase {

    Mono<CreditView> execute(GetCreditQuery query);
}
