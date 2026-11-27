package com.banking.creditservice.infrastructure.adapter.in.kafka;

import com.banking.creditservice.application.model.RecordedOperation;
import com.banking.creditservice.infrastructure.adapter.in.BusinessFailure;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The answer to a payment command, as it is written in the replies topic. A paid credit carries the
 * amount, the debt left and the instant; a rejection carries the code and the message. The fields
 * of the other case are left out.
 *
 * @param type {@code CREDIT_PAID} or {@code CREDIT_PAYMENT_REJECTED}
 * @param transactionId transaction the command belongs to, also the key of the record
 * @param operationId idempotency key of the payment
 * @param amount amount paid
 * @param debtAfter outstanding debt right after the payment
 * @param occurredAt instant of the payment; the original one when the command was a repeat
 * @param code stable error code, the one the REST API answers for the same failure
 * @param message text for a person
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record CreditReply(
    String type,
    UUID transactionId,
    UUID operationId,
    BigDecimal amount,
    BigDecimal debtAfter,
    Instant occurredAt,
    String code,
    String message) {

  static final String TYPE_PAID = "CREDIT_PAID";
  static final String TYPE_REJECTED = "CREDIT_PAYMENT_REJECTED";

  /** The payment is recorded: now, or by an earlier command with the same operationId. */
  static CreditReply paid(UUID transactionId, RecordedOperation payment) {
    return new CreditReply(
        TYPE_PAID,
        transactionId,
        payment.operationId(),
        payment.amount(),
        payment.debtAfter(),
        payment.occurredAt(),
        null,
        null);
  }

  /** The business rejected the payment and nothing was written. */
  static CreditReply rejected(UUID transactionId, UUID operationId, BusinessFailure failure) {
    return new CreditReply(
        TYPE_REJECTED,
        transactionId,
        operationId,
        null,
        null,
        null,
        failure.code(),
        failure.message());
  }
}
