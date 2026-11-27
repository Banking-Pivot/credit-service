package com.banking.creditservice.application.handler.command;

import com.banking.creditservice.application.command.PayCreditCommand;
import com.banking.creditservice.application.model.OperationResult;
import com.banking.creditservice.application.port.in.PayCreditUseCase;
import java.math.BigDecimal;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * Pays debt of a loan or card. Uses only the state kept by Credit Service. The payment does not
 * name who pays, so anybody can pay the product of another customer.
 */
public class PayCreditHandler implements PayCreditUseCase {

  private final CreditOperationExecutor executor;
  private final BigDecimal minimumPaymentRate;

  /**
   * Takes the executor that posts the payment once and the share of the debt a payment must reach
   * to count as the payment of the cycle.
   */
  public PayCreditHandler(CreditOperationExecutor executor, BigDecimal minimumPaymentRate) {
    this.executor = Objects.requireNonNull(executor);
    this.minimumPaymentRate = Objects.requireNonNull(minimumPaymentRate);
  }

  @Override
  public Mono<OperationResult> execute(PayCreditCommand command) {
    return Mono.fromCallable(command::toRequest)
        .flatMap(
            request ->
                executor.execute(
                    request,
                    (credit, amount, currency, context) ->
                        credit.pay(amount, currency, context, minimumPaymentRate)));
  }
}
