package com.banking.credit_service.infrastructure.adapter.out.postgres;

import com.banking.credit_service.application.exception.OperationAlreadyRecordedException;
import com.banking.credit_service.application.model.OperationRequest;
import com.banking.credit_service.application.model.RecordedOperation;
import com.banking.credit_service.application.port.out.OperationLogPort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Update;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.springframework.data.relational.core.query.Criteria.where;
import static org.springframework.data.relational.core.query.Query.query;

/**
 * Operation log on PostgreSQL. The primary key of {@code credit_operations} is the
 * idempotency key: a second INSERT of the same key blocks until the first transaction ends
 * and fails with a unique violation if it committed.
 */
@Component
public class PostgresOperationLogAdapter implements OperationLogPort {

    static final String PRIMARY_KEY = "pk_credit_operations";

    private final R2dbcEntityTemplate template;

    public PostgresOperationLogAdapter(R2dbcEntityTemplate template) {
        this.template = template;
    }

    @Override
    public Mono<RecordedOperation> findById(UUID operationId) {
        return template.selectOne(
                        query(where("operationId").is(operationId).and("status").is(OperationRow.COMPLETED)),
                        OperationRow.class)
                .map(OperationRow::toRecorded);
    }

    @Override
    public Mono<Void> claim(OperationRequest request) {
        return template.insert(OperationRow.claimed(request))
                .then()
                .onErrorMap(error -> PostgresErrors.isUniqueViolation(error, PRIMARY_KEY),
                        error -> new OperationAlreadyRecordedException(request.operationId(), error));
    }

    @Override
    public Mono<Void> complete(RecordedOperation operation) {
        Update update = Update.update("status", OperationRow.COMPLETED)
                .set("movementId", operation.movementId())
                .set("debtAfter", operation.debtAfter())
                .set("occurredAt", operation.occurredAt());
        if (operation.availableCreditAfter() != null) {
            update = update.set("availableCreditAfter", operation.availableCreditAfter());
        }
        return template.update(OperationRow.class)
                .matching(query(where("operationId").is(operation.operationId())
                        .and("status").is(OperationRow.PROCESSING)))
                .apply(update)
                .flatMap(rows -> rows == 1
                        ? Mono.<Void>empty()
                        : Mono.error(new IllegalStateException(
                        "Operation " + operation.operationId() + " was not claimed by this transaction.")));
    }
}
