package com.banking.credit_service.application.port.in;

import com.banking.credit_service.application.model.CreditView;
import com.banking.credit_service.application.model.ResultPage;
import com.banking.credit_service.application.query.ListCreditsQuery;
import reactor.core.publisher.Mono;

/** Query: a page of products matching the filters, with the total count. */
public interface ListCreditsUseCase {

    Mono<ResultPage<CreditView>> execute(ListCreditsQuery query);
}
