package com.banking.creditservice.application.handler.command;

import com.banking.creditservice.application.command.UpdateCreditCommand;
import com.banking.creditservice.application.exception.CreditConcurrentModificationException;
import com.banking.creditservice.application.exception.CreditNotFoundException;
import com.banking.creditservice.application.model.CreditView;
import com.banking.creditservice.application.port.in.UpdateCreditUseCase;
import com.banking.creditservice.application.port.out.CreditWritePort;
import com.banking.creditservice.application.port.out.TransactionPort;
import com.banking.creditservice.application.support.BusinessCalendar;
import com.banking.creditservice.domain.model.Credit;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * Replaces the editable terms: payment day, and the limit of a card.
 *
 * <p>The row is locked, so no charge can raise the debt between the limit check and the write.
 * Order: 404 missing, 412 stale revision, 409 closed, 422 rules. Sending the current terms changes
 * nothing and keeps the revision.
 */
public class UpdateCreditHandler implements UpdateCreditUseCase {

  private final CreditWritePort credits;
  private final TransactionPort transactions;
  private final BusinessCalendar calendar;

  /**
   * Takes the port that locks and updates products, the transaction boundary and the clock that
   * dates the change.
   */
  public UpdateCreditHandler(
      CreditWritePort credits, TransactionPort transactions, BusinessCalendar calendar) {
    this.credits = Objects.requireNonNull(credits);
    this.transactions = Objects.requireNonNull(transactions);
    this.calendar = Objects.requireNonNull(calendar);
  }

  @Override
  public Mono<CreditView> execute(UpdateCreditCommand command) {
    UUID creditId = command.creditId();
    return transactions
        .inTransaction(
            () ->
                credits
                    .lockById(creditId)
                    .switchIfEmpty(Mono.error(() -> new CreditNotFoundException(creditId)))
                    .flatMap(locked -> apply(locked, command)))
        .map(credit -> CreditView.from(credit, calendar.today()));
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
