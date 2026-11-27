package com.banking.creditservice.application.port.out;

import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * Runs work in one database transaction. Any error rolls everything back, so a change is written
 * completely or not at all.
 */
public interface TransactionPort {

  <T> Mono<T> inTransaction(Supplier<Mono<T>> work);
}
