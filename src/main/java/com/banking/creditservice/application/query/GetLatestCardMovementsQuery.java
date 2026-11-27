package com.banking.creditservice.application.query;

import java.util.Objects;
import java.util.UUID;

/** The last movements of a credit card, newest first. */
public record GetLatestCardMovementsQuery(UUID creditId) {

  /** Movements returned at most. */
  public static final int LIMIT = 10;

  public GetLatestCardMovementsQuery {
    Objects.requireNonNull(creditId, "creditId");
  }
}
