package com.banking.credit_service.domain.model;

import java.util.Objects;
import java.util.Optional;

/**
 * A new product and the movement that records its opening debt: the disbursement of a
 * loan. A card opens without movements.
 */
public record OpenedCredit(Credit credit, Optional<Movement> disbursement) {

    public OpenedCredit {
        Objects.requireNonNull(credit, "credit");
        Objects.requireNonNull(disbursement, "disbursement");
    }
}
