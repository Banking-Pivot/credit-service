package com.banking.credit_service.application.port.out;

import com.banking.credit_service.domain.model.CreditType;
import com.banking.credit_service.domain.model.CurrencyCode;
import com.banking.credit_service.domain.model.MovementType;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Totals for the product report, read from one consistent view of the data. */
public interface ProductReportPort {

    /**
     * Product and movement totals by product type and currency.
     *
     * @param start first instant of the range
     * @param end   first instant after the range
     */
    Mono<ReportTotals> totals(Instant start, Instant end);

    /** Only the combinations with data; the caller fills the rest with zeros. */
    record ReportTotals(List<CreditTotals> credits, List<MovementTotals> movements) {

        public ReportTotals {
            credits = List.copyOf(credits);
            movements = List.copyOf(movements);
        }
    }

    /** Products of one type and currency. */
    record CreditTotals(
            CreditType type,
            CurrencyCode currency,
            long opened,
            long closed,
            long activeAtEnd,
            BigDecimal outstandingDebtAtEnd
    ) {
    }

    /** Movements of one type for one product type and currency. */
    record MovementTotals(
            CreditType creditType,
            CurrencyCode currency,
            MovementType type,
            long count,
            BigDecimal totalAmount
    ) {
    }
}
