-- Credit Service schema (PostgreSQL database credits_db, exclusive to this service).
--
-- Spring Boot runs this script over R2DBC at every startup (spring.sql.init.mode: always in
-- application.yaml). Every statement is IF NOT EXISTS: on an empty database it creates the
-- schema, on a restart it leaves the existing tables and rows as they are. It does not change
-- a table that already exists: a new column or constraint needs its ALTER TABLE run by hand
-- on every existing credits_db.
--
-- Money is NUMERIC(14, 2): twelve integer digits and two decimals, the largest principal,
-- limit or operation amount the contract accepts. The debt never exceeds the principal or
-- the limit, so it fits too. Requests with more than two decimals are rejected before they
-- reach the database; nothing is rounded.
-- Instants are TIMESTAMPTZ and the application writes them in UTC.
-- Customer IDs reference Customer Service: no foreign key leaves this database.

CREATE TABLE IF NOT EXISTS credits (
    id               UUID           NOT NULL,
    customer_id      UUID           NOT NULL,
    customer_type    VARCHAR(20)    NOT NULL,
    credit_type      VARCHAR(20)    NOT NULL,
    currency         CHAR(3)        NOT NULL,
    -- Loans only: the amount lent, also the opening debt.
    principal        NUMERIC(14, 2),
    -- Cards only: the largest outstanding debt allowed.
    credit_limit     NUMERIC(14, 2),
    outstanding_debt NUMERIC(14, 2) NOT NULL,
    payment_due_day  INTEGER        NOT NULL,
    status           VARCHAR(20)    NOT NULL,
    opened_at        TIMESTAMPTZ    NOT NULL,
    updated_at       TIMESTAMPTZ    NOT NULL,
    closed_at        TIMESTAMPTZ,
    -- Revision exposed as ETag; it changes with every change, debt included.
    version          BIGINT         NOT NULL,
    CONSTRAINT pk_credits PRIMARY KEY (id),
    CONSTRAINT ck_credits_type CHECK (credit_type IN ('PERSONAL_LOAN', 'BUSINESS_LOAN', 'CREDIT_CARD')),
    CONSTRAINT ck_credits_customer_type CHECK (customer_type IN ('PERSONAL', 'BUSINESS')),
    CONSTRAINT ck_credits_type_customer CHECK (
        (credit_type = 'PERSONAL_LOAN' AND customer_type = 'PERSONAL')
        OR (credit_type = 'BUSINESS_LOAN' AND customer_type = 'BUSINESS')
        OR credit_type = 'CREDIT_CARD'),
    CONSTRAINT ck_credits_currency CHECK (currency IN ('PEN', 'USD')),
    CONSTRAINT ck_credits_status CHECK (status IN ('ACTIVE', 'CLOSED')),
    CONSTRAINT ck_credits_amounts CHECK (
        (credit_type = 'CREDIT_CARD' AND principal IS NULL AND credit_limit > 0
            AND outstanding_debt <= credit_limit)
        OR (credit_type <> 'CREDIT_CARD' AND credit_limit IS NULL AND principal > 0
            AND outstanding_debt <= principal)),
    CONSTRAINT ck_credits_debt CHECK (outstanding_debt >= 0),
    CONSTRAINT ck_credits_due_day CHECK (payment_due_day BETWEEN 1 AND 28),
    CONSTRAINT ck_credits_closed_at CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    CONSTRAINT ck_credits_closed_debt CHECK (status = 'ACTIVE' OR outstanding_debt = 0),
    CONSTRAINT ck_credits_version CHECK (version >= 0)
);

-- A customer has at most one ACTIVE personal loan. The index decides when two openings
-- race; closed loans do not count. Business loans and cards are not limited.
CREATE UNIQUE INDEX IF NOT EXISTS uk_credits_personal_loan_active
    ON credits (customer_id)
    WHERE credit_type = 'PERSONAL_LOAN' AND status = 'ACTIVE';

-- GET /credits?customerId=&type=&status= ordered by opened_at, id: the call Account Service
-- makes to find an active credit card, and its count.
CREATE INDEX IF NOT EXISTS ix_credits_customer_filters ON credits (customer_id, credit_type, status, opened_at, id);

-- Product report: products opened before the end of the range.
CREATE INDEX IF NOT EXISTS ix_credits_opened_at ON credits (opened_at);

-- History of each product. Rows are only inserted, never updated or deleted. Each row keeps
-- the operation, the type, the amount, the currency, the debt right after it and its instant.
CREATE TABLE IF NOT EXISTS credit_movements (
    id            UUID           NOT NULL,
    credit_id     UUID           NOT NULL,
    -- Client key of a charge or payment; a server key for the disbursement of a loan.
    operation_id  UUID           NOT NULL,
    movement_type VARCHAR(20)    NOT NULL,
    amount        NUMERIC(14, 2) NOT NULL,
    currency      CHAR(3)        NOT NULL,
    debt_after    NUMERIC(14, 2) NOT NULL,
    description   VARCHAR(140),
    occurred_at   TIMESTAMPTZ    NOT NULL,
    CONSTRAINT pk_credit_movements PRIMARY KEY (id),
    CONSTRAINT fk_credit_movements_credit FOREIGN KEY (credit_id) REFERENCES credits (id),
    CONSTRAINT uk_credit_movements_operation UNIQUE (operation_id),
    CONSTRAINT ck_credit_movements_type CHECK (movement_type IN ('DISBURSEMENT', 'CHARGE', 'PAYMENT')),
    CONSTRAINT ck_credit_movements_currency CHECK (currency IN ('PEN', 'USD')),
    CONSTRAINT ck_credit_movements_amounts CHECK (amount > 0 AND debt_after >= 0)
);

-- History pages and the last ten movements of a card: newest first, id breaks ties.
CREATE INDEX IF NOT EXISTS ix_credit_movements_history ON credit_movements (credit_id, occurred_at DESC, id DESC);

-- Product report: movements in the range and after it.
CREATE INDEX IF NOT EXISTS ix_credit_movements_occurred_at ON credit_movements (occurred_at);

-- One row per operationId of a charge or payment, the client's idempotency key. The primary
-- key coordinates concurrent requests with the same key: the second INSERT waits for the
-- first transaction and fails if it committed. The row is inserted as PROCESSING inside the
-- transaction that changes the debt and completed before it commits, so other transactions
-- only ever see COMPLETED rows, each with the result returned on every repeat.
CREATE TABLE IF NOT EXISTS credit_operations (
    operation_id           UUID           NOT NULL,
    operation_type         VARCHAR(20)    NOT NULL,
    credit_id              UUID           NOT NULL,
    amount                 NUMERIC(14, 2) NOT NULL,
    currency               CHAR(3)        NOT NULL,
    description            VARCHAR(140),
    status                 VARCHAR(20)    NOT NULL,
    movement_id            UUID,
    debt_after             NUMERIC(14, 2),
    available_credit_after NUMERIC(14, 2),
    occurred_at            TIMESTAMPTZ,
    CONSTRAINT pk_credit_operations PRIMARY KEY (operation_id),
    -- The product row is already locked by the same transaction when the key is claimed.
    CONSTRAINT fk_credit_operations_credit FOREIGN KEY (credit_id) REFERENCES credits (id),
    CONSTRAINT fk_credit_operations_movement FOREIGN KEY (movement_id) REFERENCES credit_movements (id),
    CONSTRAINT ck_credit_operations_type CHECK (operation_type IN ('CHARGE', 'PAYMENT')),
    CONSTRAINT ck_credit_operations_status CHECK (status IN ('PROCESSING', 'COMPLETED')),
    CONSTRAINT ck_credit_operations_currency CHECK (currency IN ('PEN', 'USD')),
    CONSTRAINT ck_credit_operations_amount CHECK (amount > 0),
    CONSTRAINT ck_credit_operations_result CHECK (
        status = 'PROCESSING'
        OR (movement_id IS NOT NULL AND debt_after IS NOT NULL AND occurred_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS ix_credit_operations_credit ON credit_operations (credit_id);
