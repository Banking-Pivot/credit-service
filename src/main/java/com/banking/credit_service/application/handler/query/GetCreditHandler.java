package com.banking.credit_service.application.handler.query;

import com.banking.credit_service.application.exception.CreditNotFoundException;
import com.banking.credit_service.application.model.CreditView;
import com.banking.credit_service.application.port.in.GetCreditUseCase;
import com.banking.credit_service.application.port.out.CreditReadPort;
import com.banking.credit_service.application.query.GetCreditQuery;
import reactor.core.publisher.Mono;

import java.util.Objects;

/** One product by ID. */
public class GetCreditHandler implements GetCreditUseCase {

    private final CreditReadPort credits;

    public GetCreditHandler(CreditReadPort credits) {
        this.credits = Objects.requireNonNull(credits);
    }

    @Override
    public Mono<CreditView> execute(GetCreditQuery query) {
        return credits.findById(query.creditId())
                .switchIfEmpty(Mono.error(() -> new CreditNotFoundException(query.creditId())));
    }
}
