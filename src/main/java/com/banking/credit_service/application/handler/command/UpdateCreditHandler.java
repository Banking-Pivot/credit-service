package com.banking.credit_service.application.handler.command;

import com.banking.credit_service.application.command.UpdateCreditCommand;
import com.banking.credit_service.application.exception.CreditConcurrentModificationException;
import com.banking.credit_service.application.exception.CreditNotFoundException;
import com.banking.credit_service.application.model.CreditView;
import com.banking.credit_service.application.port.in.UpdateCreditUseCase;
import com.banking.credit_service.application.port.out.CreditWritePort;
import com.banking.credit_service.application.port.out.TransactionPort;
import com.banking.credit_service.application.support.BusinessCalendar;
import com.banking.credit_service.domain.model.Credit;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.UUID;

/**
 * Replaces the editable terms: payment day, and the limit of a card.
 *
 * <p>The row is locked, so no charge can raise the debt between the limit check and the
 * write. Order: 404 missing, 412 stale revision, 409 closed, 422 rules. Sending the
 * current terms changes nothing and keeps the revision.
 */
public class UpdateCreditHandler implements UpdateCreditUseCase {

    private final CreditWritePort credits;
    private final TransactionPort transactions;
    private final BusinessCalendar calendar;

    public UpdateCreditHandler(CreditWritePort credits, TransactionPort transactions, BusinessCalendar calendar) {
        this.credits = Objects.requireNonNull(credits);
        this.transactions = Objects.requireNonNull(transactions);
        this.calendar = Objects.requireNonNull(calendar);
    }

    @Override
    public Mono<CreditView> execute(UpdateCreditCommand command) {
        UUID creditId = command.creditId();
        return transactions.inTransaction(() -> credits.lockById(creditId)
                        .switchIfEmpty(Mono.error(() -> new CreditNotFoundException(creditId)))
                        .flatMap(locked -> apply(locked, command)))
                .map(CreditView::from);
    }

    private Mono<Credit> apply(Credit locked, UpdateCreditCommand command) {
        long lockedVersion = locked.getVersion();
        if (lockedVersion != command.expectedVersion()) {
            return Mono.error(new CreditConcurrentModificationException(locked.getId()));
        }
        if (!locked.updateTerms(command.paymentDueDay(), command.creditLimit(), calendar.now())) {
            return Mono.just(locked);
        }
        return credits.update(locked, lockedVersion).thenReturn(locked);
    }
}
