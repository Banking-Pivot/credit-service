package com.banking.credit_service.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * When and under which operation a movement is posted.
 *
 * @param description stripped; blank becomes {@code null}
 */
public record PostingContext(UUID operationId, Instant occurredAt, String description) {

    public PostingContext {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        description = description == null || description.isBlank() ? null : description.strip();
    }
}
