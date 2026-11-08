package com.banking.credit_service.support;

import org.springframework.r2dbc.core.DatabaseClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariants of the stored credit ledger, checked straight in PostgreSQL after every
 * integration test. Each query lists the rows that break one rule; all must be empty.
 */
public final class LedgerAssertions {

    private static final String DEBT_CHANGE = """
            CASE WHEN m.movement_type = 'PAYMENT' THEN -m.amount ELSE m.amount END""";

    /** Debt = disbursement + charges - payments, and it equals the debt after the last movement. */
    private static final String DEBT_MATCHES_HISTORY = """
            SELECT c.id::text AS id
              FROM credits c
              LEFT JOIN (SELECT m.credit_id, SUM(%s) AS net FROM credit_movements m GROUP BY m.credit_id) h
                     ON h.credit_id = c.id
              LEFT JOIN LATERAL (SELECT m.debt_after FROM credit_movements m WHERE m.credit_id = c.id
                                  ORDER BY m.occurred_at DESC, m.id DESC LIMIT 1) last ON TRUE
             WHERE c.outstanding_debt <> COALESCE(h.net, 0)
                OR c.outstanding_debt <> COALESCE(last.debt_after, 0)
            """.formatted(DEBT_CHANGE);

    /** Each debt_after follows from the previous one, in history order, and the movement itself. */
    private static final String RUNNING_DEBT = """
            SELECT id::text AS id FROM (
                SELECT m.id, m.debt_after,
                       COALESCE(LAG(m.debt_after) OVER (PARTITION BY m.credit_id ORDER BY m.occurred_at, m.id), 0)
                           + %s AS expected
                  FROM credit_movements m) chain
             WHERE debt_after <> expected
            """.formatted(DEBT_CHANGE);

    /** Movements of one product never share an instant, so the history order is the posting order. */
    private static final String DISTINCT_INSTANTS = """
            SELECT credit_id::text AS id FROM credit_movements
             GROUP BY credit_id, occurred_at HAVING COUNT(*) > 1
            """;

    /** Every operation is completed and its result is its own movement. */
    private static final String OPERATION_MATCHES_MOVEMENT = """
            SELECT o.operation_id::text AS id
              FROM credit_operations o
              LEFT JOIN credit_movements m ON m.id = o.movement_id
             WHERE o.status <> 'COMPLETED' OR m.id IS NULL
                OR m.operation_id <> o.operation_id OR m.credit_id <> o.credit_id
                OR m.movement_type <> o.operation_type OR m.amount <> o.amount
                OR m.currency <> o.currency OR m.debt_after <> o.debt_after OR m.occurred_at <> o.occurred_at
            """;

    /** Charges and payments have an operation; a disbursement never has one. */
    private static final String MOVEMENT_HAS_OPERATION = """
            SELECT m.id::text AS id
              FROM credit_movements m
              LEFT JOIN credit_operations o ON o.operation_id = m.operation_id
             WHERE (m.movement_type = 'DISBURSEMENT') <> (o.operation_id IS NULL)
            """;

    /** A loan has exactly one disbursement, its principal; a card has none. Same currency everywhere. */
    private static final String OPENING_DEBT = """
            SELECT c.id::text AS id
              FROM credits c
              LEFT JOIN credit_movements d ON d.credit_id = c.id AND d.movement_type = 'DISBURSEMENT'
             GROUP BY c.id, c.credit_type, c.principal
            HAVING (c.credit_type = 'CREDIT_CARD' AND COUNT(d.id) <> 0)
                OR (c.credit_type <> 'CREDIT_CARD' AND (COUNT(d.id) <> 1 OR MAX(d.amount) <> c.principal))
            UNION
            SELECT c.id::text FROM credits c JOIN credit_movements m ON m.credit_id = c.id
             WHERE m.currency <> c.currency
            """;

    private LedgerAssertions() {
    }

    public static void assertConsistent(DatabaseClient client) {
        assertThat(ids(client, DEBT_MATCHES_HISTORY)).as("products whose debt differs from history").isEmpty();
        assertThat(ids(client, RUNNING_DEBT)).as("movements with a wrong debt_after").isEmpty();
        assertThat(ids(client, DISTINCT_INSTANTS)).as("products with two movements at one instant").isEmpty();
        assertThat(ids(client, OPERATION_MATCHES_MOVEMENT)).as("operations without a matching movement").isEmpty();
        assertThat(ids(client, MOVEMENT_HAS_OPERATION)).as("movements with a wrong operation link").isEmpty();
        assertThat(ids(client, OPENING_DEBT)).as("products with a wrong opening debt or currency").isEmpty();
    }

    private static List<String> ids(DatabaseClient client, String sql) {
        return client.sql(sql).map(row -> row.get("id", String.class)).all().collectList().block();
    }
}
