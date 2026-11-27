package com.banking.creditservice.infrastructure.adapter.in.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * The command "pay this credit" as Transaction Service writes it in the commands topic. Fields the
 * service does not know are ignored, so the sender can add some without breaking it.
 *
 * <p>Identifiers and the currency arrive as text and are checked by {@link
 * PayCreditCommandProcessor}: a command that names its transaction can be answered with a rejection
 * even when the rest of it is wrong.
 *
 * @param type {@code PAY_CREDIT}
 * @param transactionId transaction the answer is for
 * @param operationId idempotency key of the payment
 * @param creditId product to pay
 * @param amount amount to pay, a JSON number
 * @param currency currency of the amount
 * @param description free text kept with the movement, optional
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record PayCreditMessage(
    String type,
    String transactionId,
    String operationId,
    String creditId,
    BigDecimal amount,
    String currency,
    String description) {}
