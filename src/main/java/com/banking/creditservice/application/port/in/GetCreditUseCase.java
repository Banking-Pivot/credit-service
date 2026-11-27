package com.banking.creditservice.application.port.in;

import com.banking.creditservice.application.model.CreditView;
import com.banking.creditservice.application.query.GetCreditQuery;
import reactor.core.publisher.Mono;

/** Query: one product by ID. */
public interface GetCreditUseCase {

  Mono<CreditView> execute(GetCreditQuery query);
}
