package com.banking.credit_service.application.port.out;

import com.banking.credit_service.application.model.OperationRequest;
import com.banking.credit_service.application.model.RecordedOperation;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Charges and payments keyed by operationId, the idempotency key. A unique constraint on
 * the key coordinates concurrent requests.
 */
public interface OperationLogPort {

    /** Completed operation recorded under the key, empty when there is none. */
    Mono<RecordedOperation> findById(UUID operationId);

    /**
     * Reserves the key inside the transaction. A concurrent transaction with the same key
     * waits here until the first one ends; if the first one committed, this errors with
     * {@code OperationAlreadyRecordedException}.
     */
    Mono<Void> claim(OperationRequest request);

    /** Stores the result of the claimed operation and marks it completed, in the same transaction. */
    Mono<Void> complete(RecordedOperation operation);
}
