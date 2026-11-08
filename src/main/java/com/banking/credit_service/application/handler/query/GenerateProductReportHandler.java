package com.banking.credit_service.application.handler.query;

import com.banking.credit_service.application.model.ProductReport;
import com.banking.credit_service.application.port.in.GenerateProductReportUseCase;
import com.banking.credit_service.application.port.out.ProductReportPort;
import com.banking.credit_service.application.query.GenerateProductReportQuery;
import com.banking.credit_service.application.support.BusinessCalendar;
import com.banking.credit_service.domain.model.Amounts;
import com.banking.credit_service.domain.model.CreditType;
import com.banking.credit_service.domain.model.CurrencyCode;
import com.banking.credit_service.domain.model.MovementType;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Report by product type and currency for a range of business dates.
 *
 * <p>The range covers the instants from the start of {@code from} up to, not including,
 * the start of the day after {@code to}, in the business zone. The port returns the totals
 * that have data; this handler fills the full type by currency grid so every line and
 * every movement type appears, with zeros where nothing happened.
 */
public class GenerateProductReportHandler implements GenerateProductReportUseCase {

    private final ProductReportPort reports;
    private final BusinessCalendar calendar;

    public GenerateProductReportHandler(ProductReportPort reports, BusinessCalendar calendar) {
        this.reports = Objects.requireNonNull(reports);
        this.calendar = Objects.requireNonNull(calendar);
    }

    @Override
    public Mono<ProductReport> execute(GenerateProductReportQuery query) {
        return reports.totals(calendar.startOf(query.from()), calendar.startOf(query.to().plusDays(1)))
                .map(totals -> new ProductReport(query.from(), query.to(), calendar.zone(), calendar.now(),
                        lines(totals.credits(), totals.movements())));
    }

    private static List<ProductReport.Line> lines(
            List<ProductReportPort.CreditTotals> creditTotals,
            List<ProductReportPort.MovementTotals> movementTotals
    ) {
        Map<Key, ProductReportPort.CreditTotals> creditsByKey = creditTotals.stream()
                .collect(Collectors.toMap(t -> new Key(t.type(), t.currency()), Function.identity()));
        Map<Key, Map<MovementType, ProductReportPort.MovementTotals>> movementsByKey = movementTotals.stream()
                .collect(Collectors.groupingBy(t -> new Key(t.creditType(), t.currency()),
                        Collectors.toMap(ProductReportPort.MovementTotals::type, Function.identity())));

        List<ProductReport.Line> lines = new ArrayList<>();
        for (CreditType type : CreditType.values()) {
            for (CurrencyCode currency : CurrencyCode.values()) {
                Key key = new Key(type, currency);
                lines.add(line(key, creditsByKey.get(key), movementsByKey.getOrDefault(key, Map.of())));
            }
        }
        return lines;
    }

    private static ProductReport.Line line(
            Key key,
            ProductReportPort.CreditTotals credits,
            Map<MovementType, ProductReportPort.MovementTotals> movementsByType
    ) {
        List<ProductReport.MovementTotals> movements = Arrays.stream(MovementType.values())
                .map(type -> {
                    ProductReportPort.MovementTotals totals = movementsByType.get(type);
                    return totals == null
                            ? new ProductReport.MovementTotals(type, 0, Amounts.ZERO)
                            : new ProductReport.MovementTotals(type, totals.count(), totals.totalAmount());
                })
                .toList();
        return credits == null
                ? new ProductReport.Line(key.type(), key.currency(), 0, 0, 0, Amounts.ZERO, movements)
                : new ProductReport.Line(key.type(), key.currency(), credits.opened(), credits.closed(),
                credits.activeAtEnd(), credits.outstandingDebtAtEnd(), movements);
    }

    private record Key(CreditType type, CurrencyCode currency) {
    }
}
