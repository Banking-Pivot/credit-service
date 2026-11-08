package com.banking.credit_service.application.handler.command;

import com.banking.credit_service.application.command.CloseCreditCommand;
import com.banking.credit_service.application.exception.CreditConcurrentModificationException;
import com.banking.credit_service.application.exception.CreditNotFoundException;
import com.banking.credit_service.application.port.in.CloseCreditUseCase;
import com.banking.credit_service.application.port.out.CreditWritePort;
import com.banking.credit_service.application.port.out.TransactionPort;
import com.banking.credit_service.application.support.BusinessCalendar;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.UUID;

/**
 * Closes a product logically: status CLOSED, history kept.
 *
 * <p>The row is locked, so no charge can arrive between the zero-debt check and the write.
 * Order: 404 missing, 412 stale revision, 422 debt not zero. Closing a product that is
 * already closed, with its current revision, changes nothing.
 */
public class CloseCreditHandler implements CloseCreditUseCase {

    private final CreditWritePort credits;
    private final TransactionPort transactions;
    private final BusinessCalendar calendar;

    public CloseCreditHandler(CreditWritePort credits, TransactionPort transactions, BusinessCalendar calendar) {
        this.credits = Objects.requireNonNull(credits);
        this.transactions = Objects.requireNonNull(transactions);
        this.calendar = Objects.requireNonNull(calendar);
    }

    @Override
    public Mono<Void> execute(CloseCreditCommand command) {
        UUID creditId = command.creditId();
        return transactions.inTransaction(() -> credits.lockById(creditId)
                .switchIfEmpty(Mono.error(() -> new CreditNotFoundException(creditId)))
                .flatMap(locked -> {
                    long lockedVersion = locked.getVersion();
                    if (lockedVersion != command.expectedVersion()) {
                        return Mono.error(new CreditConcurrentModificationException(creditId));
                    }
                    return locked.close(calendar.now())
                            ? credits.update(locked, lockedVersion)
                            : Mono.<Void>empty();
                }));
    }
}
