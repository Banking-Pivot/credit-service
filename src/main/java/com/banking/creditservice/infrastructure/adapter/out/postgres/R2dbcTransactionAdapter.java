package com.banking.creditservice.infrastructure.adapter.out.postgres;

import com.banking.creditservice.application.port.out.TransactionPort;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * Reactive transaction on the R2DBC connection (READ COMMITTED, PostgreSQL default). Every
 * statement of the work runs on the same connection; an error signal rolls back, completion
 * commits.
 */
@Component
public class R2dbcTransactionAdapter implements TransactionPort {

  private final TransactionalOperator operator;

  public R2dbcTransactionAdapter(ReactiveTransactionManager transactionManager) {
    this.operator = TransactionalOperator.create(transactionManager);
  }

  @Override
  public <T> Mono<T> inTransaction(Supplier<Mono<T>> work) {
    return operator.transactional(Mono.defer(work));
  }
}
