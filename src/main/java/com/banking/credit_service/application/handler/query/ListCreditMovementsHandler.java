package com.banking.credit_service.application.handler.query;

import com.banking.credit_service.application.exception.CreditNotFoundException;
import com.banking.credit_service.application.model.ResultPage;
import com.banking.credit_service.application.port.in.ListCreditMovementsUseCase;
import com.banking.credit_service.application.port.out.CreditReadPort;
import com.banking.credit_service.application.port.out.MovementReadPort;
import com.banking.credit_service.application.query.ListCreditMovementsQuery;
import com.banking.credit_service.domain.model.Movement;
import reactor.core.publisher.Mono;

import java.util.Objects;

/** Movement history of a product, newest first. A missing product is 404, not an empty page. */
public class ListCreditMovementsHandler implements ListCreditMovementsUseCase {

    private final CreditReadPort credits;
    private final MovementReadPort movements;

    public ListCreditMovementsHandler(CreditReadPort credits, MovementReadPort movements) {
        this.credits = Objects.requireNonNull(credits);
        this.movements = Objects.requireNonNull(movements);
    }

    @Override
    public Mono<ResultPage<Movement>> execute(ListCreditMovementsQuery query) {
        return credits.existsById(query.creditId())
                .flatMap(exists -> exists
                        ? movements.findPage(query.creditId(), query.pageRequest())
                        : Mono.error(new CreditNotFoundException(query.creditId())));
    }
}
