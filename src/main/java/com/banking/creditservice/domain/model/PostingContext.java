package com.banking.creditservice.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * When and under which operation a movement is posted.
 *
 * @param businessDate day of the posting in the business zone; due dates are counted from it
 * @param description stripped; blank becomes {@code null}
 */
public record PostingContext(
    UUID operationId, Instant occurredAt, LocalDate businessDate, String description) {

  /**
   * Requires the operation, the instant and the business date. A blank description becomes {@code
   * null}; any other is stripped.
   */
  public PostingContext {
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(businessDate, "businessDate");
    description = description == null || description.isBlank() ? null : description.strip();
  }
}
