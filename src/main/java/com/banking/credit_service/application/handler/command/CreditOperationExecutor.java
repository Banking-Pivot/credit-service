package com.banking.credit_service.application.handler.command;

import com.banking.credit_service.application.exception.CreditNotFoundException;
import com.banking.credit_service.application.exception.OperationAlreadyRecordedException;
import com.banking.credit_service.application.exception.OperationIdConflictException;
import com.banking.credit_service.application.model.OperationRequest;
import com.banking.credit_service.application.model.OperationResult;
import com.banking.credit_service.application.model.RecordedOperation;
import com.banking.credit_service.application.port.out.CreditWritePort;
import com.banking.credit_service.application.port.out.MovementWritePort;
import com.banking.credit_service.application.port.out.OperationLogPort;
import com.banking.credit_service.application.port.out.TransactionPort;
import com.banking.credit_service.application.support.BusinessCalendar;
import com.banking.credit_service.domain.model.Credit;
import com.banking.credit_service.domain.model.CurrencyCode;
import com.banking.credit_service.domain.model.Movement;
import com.banking.credit_service.domain.model.PostingContext;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Runs a charge or payment at most once per operationId.
 *
 * <ol>
 *   <li>A recorded key is answered from the record: same data is a replay (200), other
 *       data a conflict (409).</li>
 *   <li>Otherwise one transaction locks the product row (404 if missing), claims the key
 *       (INSERT into its primary key), validates the operation against the locked state,
 *       writes the new debt conditioned on the locked revision, appends the movement and
 *       stores the result. Any error rolls back all of it, the claim included, so a
 *       rejected or failed operation leaves the key free for a retry.</li>
 *   <li>When a concurrent request with the same key commits first, the claim fails after
 *       waiting on the index; this transaction rolls back and the request is answered from
 *       the record the winner left.</li>
 * </ol>
 *
 * <p>The row is locked before the key is claimed: a missing product answers 404 without
 * touching the key, and requests on the same product queue on one lock, so the second of
 * two charges sees the debt the first one left.
 */
public class CreditOperationExecutor {

    private final CreditWritePort credits;
    private final MovementWritePort movements;
    private final OperationLogPort operations;
    private final TransactionPort transactions;
    private final BusinessCalendar calendar;

    public CreditOperationExecutor(
            CreditWritePort credits,
            MovementWritePort movements,
            OperationLogPort operations,
            TransactionPort transactions,
            BusinessCalendar calendar
    ) {
        this.credits = Objects.requireNonNull(credits);
        this.movements = Objects.requireNonNull(movements);
        this.operations = Objects.requireNonNull(operations);
        this.transactions = Objects.requireNonNull(transactions);
        this.calendar = Objects.requireNonNull(calendar);
    }

    public Mono<OperationResult> execute(OperationRequest request, Posting posting) {
        return operations.findById(request.operationId())
                .map(recorded -> replay(request, recorded))
                .switchIfEmpty(Mono.defer(() -> runOnce(request, posting)))
                .onErrorResume(OperationAlreadyRecordedException.class, lostRace ->
                        operations.findById(request.operationId())
                                .switchIfEmpty(Mono.error(lostRace))
                                .map(recorded -> replay(request, recorded)));
    }

    private Mono<OperationResult> runOnce(OperationRequest request, Posting posting) {
        return transactions.inTransaction(() -> credits.lockById(request.creditId())
                        .switchIfEmpty(Mono.error(() -> new CreditNotFoundException(request.creditId())))
                        .flatMap(locked -> operations.claim(request)
                                .then(Mono.defer(() -> post(request, locked, posting)))))
                .map(recorded -> new OperationResult(recorded, false));
    }

    /**
     * Domain rules run on the locked state. The posting instant is taken after the lock and
     * is later than the last change of the product, so the history keeps the posting order.
     */
    private Mono<RecordedOperation> post(OperationRequest request, Credit credit, Posting posting) {
        long lockedVersion = credit.getVersion();
        PostingContext context = new PostingContext(request.operationId(),
                calendar.nowAfter(credit.getUpdatedAt()), request.description());
        Movement movement = posting.apply(credit, request.amount(), request.currency(), context);
        RecordedOperation recorded = RecordedOperation.of(request, movement, credit.availableCredit().orElse(null));
        return credits.update(credit, lockedVersion)
                .then(movements.append(movement))
                .then(operations.complete(recorded))
                .thenReturn(recorded);
    }

    private static OperationResult replay(OperationRequest request, RecordedOperation recorded) {
        if (!request.matches(recorded)) {
            throw new OperationIdConflictException(request.operationId());
        }
        return new OperationResult(recorded, true);
    }

    /** A monetary operation of the aggregate, such as {@code Credit::charge}. */
    @FunctionalInterface
    public interface Posting {

        Movement apply(Credit credit, BigDecimal amount, CurrencyCode currency, PostingContext context);
    }
}
