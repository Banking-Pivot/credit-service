package com.banking.creditservice.application.query;

import java.util.Objects;
import java.util.UUID;

/** Available credit of a card. */
public record GetAvailableCreditQuery(UUID creditId) {

  public GetAvailableCreditQuery {
    Objects.requireNonNull(creditId, "creditId");
  }
}
