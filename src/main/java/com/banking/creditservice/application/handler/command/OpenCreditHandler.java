package com.banking.creditservice.application.handler.command;

import com.banking.creditservice.application.command.OpenCreditCommand;
import com.banking.creditservice.application.model.CreditView;
import com.banking.creditservice.application.port.in.OpenCreditUseCase;
import com.banking.creditservice.application.port.out.CreditWritePort;
import com.banking.creditservice.application.port.out.CustomerDirectoryPort;
import com.banking.creditservice.application.port.out.MovementWritePort;
import com.banking.creditservice.application.port.out.TransactionPort;
import com.banking.creditservice.application.support.BusinessCalendar;
import com.banking.creditservice.domain.exception.CreditRuleViolationException;
import com.banking.creditservice.domain.exception.RuleViolation;
import com.banking.creditservice.domain.model.Credit;
import com.banking.creditservice.domain.model.vo.CreditTerms;
import com.banking.creditservice.domain.model.vo.OpenedCredit;
import com.banking.creditservice.domain.model.vo.PostingContext;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * Opens a credit product.
 *
 * <p>Order: the terms of the request without any call (422 INVALID_PRODUCT_TERMS or
 * INVALID_AMOUNT), then Customer Service (422 when the customer is unknown, inactive or cannot hold
 * the product; 503 when Customer Service is unavailable), then the debts the customer has here (422
 * OVERDUE_DEBT when any product is overdue), and only then one transaction that inserts the product
 * and, for a loan, its disbursement. Nothing is written before the customer answers. The partial
 * unique index of PostgreSQL rejects a second active personal loan even when two openings race
 * (409).
 */
public class OpenCreditHandler implements OpenCreditUseCase {

  private final CreditWritePort credits;
  private final MovementWritePort movements;
  private final TransactionPort transactions;
  private final CustomerDirectoryPort customers;
  private final BusinessCalendar calendar;

  /**
   * Takes the stores of products and movements, the transaction they share, the customer directory
   * and the clock that dates the opening.
   */
  public OpenCreditHandler(
      CreditWritePort credits,
      MovementWritePort movements,
      TransactionPort transactions,
      CustomerDirectoryPort customers,
      BusinessCalendar calendar) {
    this.credits = Objects.requireNonNull(credits);
    this.movements = Objects.requireNonNull(movements);
    this.transactions = Objects.requireNonNull(transactions);
    this.customers = Objects.requireNonNull(customers);
    this.calendar = Objects.requireNonNull(calendar);
  }

  @Override
  public Mono<CreditView> execute(OpenCreditCommand command) {
    return Mono.fromCallable(
            () ->
                CreditTerms.forOpening(
                    command.type(),
                    command.principal(),
                    command.creditLimit(),
                    command.paymentDueDay()))
        .flatMap(
            terms ->
                customers
                    .findById(command.customerId())
                    .switchIfEmpty(
                        Mono.error(
                            () ->
                                new CreditRuleViolationException(
                                    RuleViolation.CUSTOMER_NOT_FOUND,
                                    "The customer does not exist.")))
                    .map(
                        customer ->
                            Credit.open(
                                UUID.randomUUID(),
                                customer.toApplicant(),
                                command.type(),
                                command.currency(),
                                terms,
                                new PostingContext(
                                    UUID.randomUUID(), calendar.now(), calendar.today(), null))))
        .flatMap(this::requireNoOverdueDebt)
        .flatMap(opened -> transactions.inTransaction(() -> store(opened)))
        .map(credit -> CreditView.from(credit, calendar.today()));
  }

  private Mono<OpenedCredit> requireNoOverdueDebt(OpenedCredit opened) {
    return credits
        .hasOverdueDebt(opened.credit().getCustomerId(), calendar.today())
        .flatMap(
            overdue ->
                Boolean.TRUE.equals(overdue)
                    ? Mono.error(
                        new CreditRuleViolationException(
                            RuleViolation.OVERDUE_DEBT,
                            "The customer has overdue debt in a credit product."))
                    : Mono.just(opened));
  }

  private Mono<Credit> store(OpenedCredit opened) {
    Mono<Void> stored = credits.insert(opened.credit());
    return opened
        .disbursement()
        .map(movement -> stored.then(movements.append(movement)))
        .orElse(stored)
        .thenReturn(opened.credit());
  }
}
