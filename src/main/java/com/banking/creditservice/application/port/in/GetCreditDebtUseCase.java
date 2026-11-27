package com.banking.creditservice.application.port.in;

import com.banking.creditservice.application.model.CreditBalanceView;
import com.banking.creditservice.application.query.GetCreditDebtQuery;
import reactor.core.publisher.Mono;

/** Query: outstanding debt of a loan or card. */
public interface GetCreditDebtUseCase {

  Mono<CreditBalanceView> execute(GetCreditDebtQuery query);
}
