package com.banking.creditservice.application.port.in;

import com.banking.creditservice.application.model.CreditView;
import com.banking.creditservice.application.model.ResultPage;
import com.banking.creditservice.application.query.ListCreditsQuery;
import reactor.core.publisher.Mono;

/** Query: a page of products matching the filters, with the total count. */
public interface ListCreditsUseCase {

  Mono<ResultPage<CreditView>> execute(ListCreditsQuery query);
}
