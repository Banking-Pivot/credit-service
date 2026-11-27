package com.banking.creditservice.application.handler.query;

import com.banking.creditservice.application.model.CreditView;
import com.banking.creditservice.application.model.ResultPage;
import com.banking.creditservice.application.port.in.ListCreditsUseCase;
import com.banking.creditservice.application.port.out.CreditReadPort;
import com.banking.creditservice.application.query.ListCreditsQuery;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * Products matching the filters. A customer without matches gets an empty page with total zero,
 * never an error: Account Service relies on that to check for an active card.
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
