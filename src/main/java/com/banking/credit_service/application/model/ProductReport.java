package com.banking.credit_service.application.model;

import com.banking.credit_service.domain.model.CreditType;
import com.banking.credit_service.domain.model.CurrencyCode;
import com.banking.credit_service.domain.model.MovementType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Report by product type and currency for a range of business dates. It has one line for
 * every combination, with zeros where nothing happened.
 */
public record ProductReport(LocalDate from, LocalDate to, ZoneId businessZone, Instant generatedAt, List<Line> lines) {

    public ProductReport {
        lines = List.copyOf(lines);
    }

    /** Totals of one product type in one currency. */
    public record Line(
            CreditType type,
            CurrencyCode currency,
            long creditsOpened,
            long creditsClosed,
            long activeCreditsAtEnd,
            BigDecimal outstandingDebtAtEnd,
            List<MovementTotals> movements
    ) {

        public Line {
            movements = List.copyOf(movements);
        }
    }

    /** Count and amount of one movement type. */
    public record MovementTotals(MovementType type, long count, BigDecimal totalAmount) {
    }
}
