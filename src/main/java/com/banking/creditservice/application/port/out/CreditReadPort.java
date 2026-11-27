package com.banking.creditservice.application.port.out;

import com.banking.creditservice.application.model.CreditView;
import com.banking.creditservice.application.model.ResultPage;
import com.banking.creditservice.application.query.ListCreditsQuery;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Read side of credit products. Reads never lock. */
public interface CreditReadPort {

  Mono<CreditView> findById(UUID creditId);

  Mono<Boolean> existsById(UUID creditId);

  /** Ordered by openedAt, then id; the total counts every match regardless of the page. */
  Mono<ResultPage<CreditView>> findPage(ListCreditsQuery query);
}
