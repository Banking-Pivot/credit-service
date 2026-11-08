package com.banking.credit_service.application.port.out;

import com.banking.credit_service.domain.model.Credit;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Write side of credit products. Used inside a transaction of {@link TransactionPort}. */
public interface CreditWritePort {

    /**
     * Inserts a new product. A second ACTIVE personal loan of the same customer fails with
     * {@code DuplicatePersonalLoanException}, also when two openings race.
     */
    Mono<Void> insert(Credit credit);

    /** Reads the product and locks its row until the transaction ends; empty when it does not exist. */
    Mono<Credit> lockById(UUID creditId);

    /**
     * Stores limit, debt, payment day, status and revision, only if the stored revision is
     * still {@code expectedVersion}; otherwise {@code CreditConcurrentModificationException}.
     */
    Mono<Void> update(Credit credit, long expectedVersion);
}
