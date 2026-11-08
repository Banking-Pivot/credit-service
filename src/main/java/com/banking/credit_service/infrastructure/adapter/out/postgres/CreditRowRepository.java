package com.banking.credit_service.infrastructure.adapter.out.postgres;

import org.springframework.data.relational.core.sql.LockMode;
import org.springframework.data.relational.repository.Lock;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Spring Data access to {@code credits}, used for the locked read. */
public interface CreditRowRepository extends ReactiveCrudRepository<CreditRow, UUID> {

    /**
     * {@code SELECT ... FROM credits WHERE id = ? FOR UPDATE}. Derived query; the lock lasts
     * until the surrounding transaction ends.
     */
    @Lock(LockMode.PESSIMISTIC_WRITE)
    Mono<CreditRow> findForUpdateById(UUID id);
}
