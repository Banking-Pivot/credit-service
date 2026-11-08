package com.banking.credit_service.infrastructure.adapter.out.postgres;

import com.banking.credit_service.application.port.out.ProductReportPort;
import com.banking.credit_service.domain.model.Amounts;
import com.banking.credit_service.domain.model.CreditType;
import com.banking.credit_service.domain.model.CurrencyCode;
import com.banking.credit_service.domain.model.MovementType;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.data.relational.core.query.Criteria.where;
import static org.springframework.data.relational.core.query.Query.query;

/**
 * Product report with Spring Data R2DBC criteria only: no SQL written by hand and no
 * {@code @Query}, as the project rules require.
 *
 * <p>Three selects run in one read-only REPEATABLE READ transaction, so debts and movements
 * come from the same snapshot while operations keep committing: the products opened before
 * the end of the range, the movements in the range and the movements after it. The debt of
 * a product at the end of the range is its current debt minus what the later movements
 * changed. Movements are aggregated as they stream from the database; only the totals and
 * the products stay in memory.
 */
@Component
public class PostgresProductReportAdapter implements ProductReportPort {

    private final R2dbcEntityTemplate template;
    private final TransactionalOperator snapshot;

    public PostgresProductReportAdapter(R2dbcEntityTemplate template, ReactiveTransactionManager transactionManager) {
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        definition.setReadOnly(true);
        this.template = template;
        this.snapshot = TransactionalOperator.create(transactionManager, definition);
    }

    @Override
    public Mono<ReportTotals> totals(Instant start, Instant end) {
        return snapshot.transactional(Mono.defer(() -> creditsOpenedBefore(end)
                .flatMap(credits -> debtChangesFrom(end)
                        .flatMap(laterChanges -> movementTotals(credits, start, end)
                                .map(movements -> new ReportTotals(
                                        creditTotals(credits.values(), start, end, laterChanges), movements))))));
    }

    private Mono<Map<UUID, CreditRow>> creditsOpenedBefore(Instant end) {
        return template.select(CreditRow.class)
                .matching(query(where("openedAt").lessThan(end)))
                .all()
                .collectMap(CreditRow::id);
    }

    /** Net debt change of each product caused by movements at or after the end of the range. */
    private Mono<Map<UUID, BigDecimal>> debtChangesFrom(Instant end) {
        return template.select(MovementRow.class)
                .matching(query(where("occurredAt").greaterThanOrEquals(end)))
                .all()
                .collect(Collectors.groupingBy(MovementRow::creditId, Collectors.reducing(Amounts.ZERO,
                        movement -> movement.movementType().debtChange(movement.amount()), BigDecimal::add)));
    }

    /**
     * Movement totals in the range. Every movement belongs to a product opened before the end
     * of the range, because no movement is dated before the opening of its product.
     */
    private Mono<List<MovementTotals>> movementTotals(Map<UUID, CreditRow> credits, Instant start, Instant end) {
        return template.select(MovementRow.class)
                .matching(query(where("occurredAt").greaterThanOrEquals(start).and("occurredAt").lessThan(end)))
                .all()
                .collect(Collectors.groupingBy(
                        movement -> MovementKey.of(Objects.requireNonNull(credits.get(movement.creditId()),
                                "movement dated before the opening of its product"), movement.movementType()),
                        Collectors.reducing(MovementSums.NONE, MovementSums::of, MovementSums::plus)))
                .map(totals -> totals.entrySet().stream()
                        .map(entry -> new MovementTotals(entry.getKey().creditType(), entry.getKey().currency(),
                                entry.getKey().type(), entry.getValue().count(), entry.getValue().amount()))
                        .toList());
    }

    private static List<CreditTotals> creditTotals(
            Collection<CreditRow> credits,
            Instant start,
            Instant end,
            Map<UUID, BigDecimal> laterChanges
    ) {
        return credits.stream()
                .collect(Collectors.groupingBy(credit -> new ProductKey(credit.creditType(), credit.currency()),
                        Collectors.reducing(CreditSums.NONE,
                                credit -> CreditSums.of(credit, start, end, laterChanges), CreditSums::plus)))
                .entrySet().stream()
                .map(entry -> new CreditTotals(entry.getKey().type(), entry.getKey().currency(),
                        entry.getValue().opened(), entry.getValue().closed(), entry.getValue().activeAtEnd(),
                        entry.getValue().debtAtEnd()))
                .toList();
    }

    private record ProductKey(CreditType type, CurrencyCode currency) {
    }

    private record MovementKey(CreditType creditType, CurrencyCode currency, MovementType type) {

        static MovementKey of(CreditRow credit, MovementType type) {
            return new MovementKey(credit.creditType(), credit.currency(), type);
        }
    }

    private record MovementSums(long count, BigDecimal amount) {

        static final MovementSums NONE = new MovementSums(0, Amounts.ZERO);

        static MovementSums of(MovementRow movement) {
            return new MovementSums(1, movement.amount());
        }

        MovementSums plus(MovementSums other) {
            return new MovementSums(count + other.count, amount.add(other.amount));
        }
    }

    private record CreditSums(long opened, long closed, long activeAtEnd, BigDecimal debtAtEnd) {

        static final CreditSums NONE = new CreditSums(0, 0, 0, Amounts.ZERO);

        static CreditSums of(CreditRow credit, Instant start, Instant end, Map<UUID, BigDecimal> laterChanges) {
            Instant closedAt = credit.closedAt();
            boolean closedInRange = closedAt != null && !closedAt.isBefore(start) && closedAt.isBefore(end);
            boolean activeAtEnd = closedAt == null || !closedAt.isBefore(end);
            return new CreditSums(
                    credit.openedAt().isBefore(start) ? 0 : 1,
                    closedInRange ? 1 : 0,
                    activeAtEnd ? 1 : 0,
                    credit.outstandingDebt().subtract(laterChanges.getOrDefault(credit.id(), Amounts.ZERO)));
        }

        CreditSums plus(CreditSums other) {
            return new CreditSums(opened + other.opened, closed + other.closed, activeAtEnd + other.activeAtEnd,
                    debtAtEnd.add(other.debtAtEnd));
        }
    }
}
