package com.banking.credit_service.application.handler.query;

import com.banking.credit_service.application.model.CreditView;
import com.banking.credit_service.application.model.ResultPage;
import com.banking.credit_service.application.port.in.ListCreditsUseCase;
import com.banking.credit_service.application.port.out.CreditReadPort;
import com.banking.credit_service.application.query.ListCreditsQuery;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * Products matching the filters. A customer without matches gets an empty page with
 * total zero, never an error: Account Service relies on that to check for an active card.
 */
public class ListCreditsHandler implements ListCreditsUseCase {

    private final CreditReadPort credits;

    public ListCreditsHandler(CreditReadPort credits) {
        this.credits = Objects.requireNonNull(credits);
    }

    @Override
    public Mono<ResultPage<CreditView>> execute(ListCreditsQuery query) {
        return credits.findPage(query);
    }
}
