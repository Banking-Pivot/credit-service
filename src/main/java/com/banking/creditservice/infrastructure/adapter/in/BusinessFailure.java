package com.banking.creditservice.infrastructure.adapter.in;

import com.banking.creditservice.application.exception.CreditNotFoundException;
import com.banking.creditservice.application.exception.OperationIdConflictException;
import com.banking.creditservice.domain.exception.CreditClosedException;
import com.banking.creditservice.domain.exception.CreditRuleViolationException;
import java.util.Optional;

/**
 * How a request the business rejected is named to whoever sent it: the same code and the same
 * message whether it came by REST or by Kafka.
 *
 * @param code stable error code
 * @param message text for a person; it never repeats free text of the request
 */
public record BusinessFailure(String code, String message) {

  /** The credit product of the request does not exist. */
  public static final BusinessFailure CREDIT_NOT_FOUND =
      new BusinessFailure("CREDIT_NOT_FOUND", "The credit product does not exist.");

  /** The credit product is closed and accepts no more operations. */
  public static final BusinessFailure CREDIT_CLOSED =
      new BusinessFailure("CREDIT_CLOSED", "The credit product is closed.");

  /** The operationId already identifies an operation with other data. */
  public static final BusinessFailure OPERATION_ID_CONFLICT =
      new BusinessFailure(
          "OPERATION_ID_CONFLICT", "The operationId was already used with different data.");

  /**
   * A broken domain rule: the name of the rule is the code and the domain gives the message.
   *
   * @param rule the rule violation raised by the domain
   * @return its code and message
   */
  public static BusinessFailure ofRule(CreditRuleViolationException rule) {
    // Domain messages name rules, fields and stored values only.
    return new BusinessFailure(rule.violation().name(), rule.getMessage());
  }

  /**
   * The answer to a failed charge or payment, when the failure is a decision of the business.
   *
   * @param failure what the use case failed with
   * @return the code and message to answer with, or empty when the failure is technical and the
   *     operation can be tried again as it is
   */
  public static Optional<BusinessFailure> of(Throwable failure) {
    if (failure instanceof CreditRuleViolationException rule) {
      return Optional.of(ofRule(rule));
    }
    if (failure instanceof CreditNotFoundException) {
      return Optional.of(CREDIT_NOT_FOUND);
    }
    if (failure instanceof CreditClosedException) {
      return Optional.of(CREDIT_CLOSED);
    }
    if (failure instanceof OperationIdConflictException) {
      return Optional.of(OPERATION_ID_CONFLICT);
    }
    return Optional.empty();
  }
}
