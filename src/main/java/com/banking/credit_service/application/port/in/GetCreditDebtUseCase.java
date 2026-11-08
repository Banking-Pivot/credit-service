package com.banking.credit_service.application.port.in;

import com.banking.credit_service.application.model.CreditBalanceView;
import com.banking.credit_service.application.query.GetCreditDebtQuery;
import reactor.core.publisher.Mono;

/** Query: outstanding debt of a loan or card. */
public interface GetCreditDebtUseCase {

    Mono<CreditBalanceView> execute(GetCreditDebtQuery query);
}
