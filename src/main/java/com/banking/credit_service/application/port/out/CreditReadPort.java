package com.banking.credit_service.application.port.out;

import com.banking.credit_service.application.model.CreditView;
import com.banking.credit_service.application.model.ResultPage;
import com.banking.credit_service.application.query.ListCreditsQuery;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Read side of credit products. Reads never lock. */
public interface CreditReadPort {

    Mono<CreditView> findById(UUID creditId);

    Mono<Boolean> existsById(UUID creditId);

    /** Ordered by openedAt, then id; the total counts every match regardless of the page. */
    Mono<ResultPage<CreditView>> findPage(ListCreditsQuery query);
}
