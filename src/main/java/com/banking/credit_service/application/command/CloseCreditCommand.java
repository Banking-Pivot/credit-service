package com.banking.credit_service.application.command;

import java.util.Objects;
import java.util.UUID;

/**
 * Closes a product logically.
 *
 * @param expectedVersion revision from If-Match
 */
public record CloseCreditCommand(UUID creditId, long expectedVersion) {

    public CloseCreditCommand {
        Objects.requireNonNull(creditId, "creditId");
    }
}
