package com.banking.creditservice.infrastructure.adapter.in.kafka;

import com.banking.creditservice.application.command.PayCreditCommand;
import com.banking.creditservice.application.port.in.PayCreditUseCase;
import com.banking.creditservice.domain.model.enums.CurrencyCode;
import com.banking.creditservice.infrastructure.adapter.in.BusinessFailure;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a record of the commands topic into a payment and the payment into its answer.
 *
 * <ul>
 *   <li>A payment that is recorded answers {@code CREDIT_PAID}. A repeat of the command finds the
 *       record by its operationId and answers the same, without paying again.
 *   <li>A payment the business rejects answers {@code CREDIT_PAYMENT_REJECTED} with the code the
 *       REST API gives for the same failure. A command that names its transaction but has wrong or
 *       missing data is rejected as {@code INVALID_REQUEST}.
 *   <li>A record that does not say which transaction it belongs to cannot be answered: it is logged
 *       and skipped.
 *   <li>A technical failure is not an answer. It is passed on, so the command is tried again.
 * </ul>
 */
@Component
class PayCreditCommandProcessor {

  static final String PAY_CREDIT = "PAY_CREDIT";

  private static final Logger LOG = LoggerFactory.getLogger(PayCreditCommandProcessor.class);
  private static final int MAX_DESCRIPTION_LENGTH = 140;
  private static final BusinessFailure INVALID_COMMAND =
      new BusinessFailure(
          "INVALID_REQUEST", "The command is malformed or contains invalid or missing fields.");

  private final PayCreditUseCase pay;
  private final JsonMapper mapper;

  PayCreditCommandProcessor(PayCreditUseCase pay, JsonMapper mapper) {
    this.pay = pay;
    this.mapper = mapper;
  }

  /**
   * Runs the command of a record.
   *
   * @param received record of the commands topic
   * @return the answer to send, or empty when the record cannot be answered and is skipped; an
   *     error when the payment failed for a technical reason
   */
  Mono<CreditReply> process(ConsumerRecord<String, String> received) {
    Optional<PayCreditMessage> read = read(received.value());
    if (read.isEmpty()) {
      return skip(received, "the value is not the JSON of a command");
    }
    PayCreditMessage message = read.get();
    if (!PAY_CREDIT.equals(message.type())) {
      return skip(received, "the type is not one this service handles");
    }
    Optional<UUID> transactionId = uuid(message.transactionId());
    Optional<UUID> operationId = uuid(message.operationId());
    if (transactionId.isEmpty() || operationId.isEmpty()) {
      return skip(received, "it has no usable transactionId and operationId");
    }
    return command(operationId.get(), message)
        .map(command -> pay(transactionId.get(), command))
        .orElseGet(
            () ->
                Mono.just(
                    CreditReply.rejected(transactionId.get(), operationId.get(), INVALID_COMMAND)));
  }

  private Mono<CreditReply> pay(UUID transactionId, PayCreditCommand command) {
    return Mono.defer(() -> pay.execute(command))
        .map(result -> CreditReply.paid(transactionId, result.operation()))
        .onErrorResume(
            failure ->
                BusinessFailure.of(failure)
                    .map(
                        rejection ->
                            Mono.just(
                                CreditReply.rejected(
                                    transactionId, command.operationId(), rejection)))
                    .orElseGet(() -> Mono.error(failure)));
  }

  // What the REST contract checks before the use case runs: required fields, a known currency and
  // the length of the description. The amount itself is checked by the domain.
  private static Optional<PayCreditCommand> command(UUID operationId, PayCreditMessage message) {
    Optional<UUID> creditId = uuid(message.creditId());
    Optional<CurrencyCode> currency = currency(message.currency());
    String description = message.description();
    if (creditId.isEmpty()
        || currency.isEmpty()
        || message.amount() == null
        || (description != null && description.length() > MAX_DESCRIPTION_LENGTH)) {
      return Optional.empty();
    }
    return Optional.of(
        new PayCreditCommand(
            operationId, creditId.get(), message.amount(), currency.get(), description));
  }

  private Optional<PayCreditMessage> read(String value) {
    if (value == null) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(mapper.readValue(value, PayCreditMessage.class));
    } catch (JacksonException unreadable) {
      return Optional.empty();
    }
  }

  // The value is never logged: it can carry the free text of a description.
  private static Mono<CreditReply> skip(ConsumerRecord<String, String> received, String reason) {
    if (LOG.isWarnEnabled()) {
      LOG.warn(
          "Command skipped, it cannot be answered: {}. topic={}, partition={}, offset={}",
          reason,
          received.topic(),
          received.partition(),
          received.offset());
    }
    return Mono.empty();
  }

  private static Optional<UUID> uuid(String text) {
    if (text == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(text));
    } catch (IllegalArgumentException notAnIdentifier) {
      return Optional.empty();
    }
  }

  private static Optional<CurrencyCode> currency(String text) {
    if (text == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(CurrencyCode.valueOf(text));
    } catch (IllegalArgumentException unknown) {
      return Optional.empty();
    }
  }
}
