-- Orchestration schema (LLD section 10).
--
-- Correction to the LLD DDL: PostgreSQL requires every PRIMARY KEY and UNIQUE constraint on a
-- partitioned table to include the partition column. payment therefore uses
-- (payment_id, created_at) and (idempotency_key, created_at). Callers always pass created_at
-- (the command's initiatedAt), so retries hit the same key. pg_partman creates monthly
-- partitions in OCI; locally a DEFAULT partition holds everything.

CREATE TABLE saga (
  saga_id        UUID PRIMARY KEY,
  saga_type      TEXT NOT NULL,        -- ONBOARDING, PAYMENT, CARD_REPLACE, FREEZE ...
  customer_id    UUID NOT NULL,
  correlation_id TEXT NOT NULL,
  current_step   TEXT NOT NULL,
  status         TEXT NOT NULL,        -- RUNNING, WAITING, COMPLETED, FAILED, COMPENSATED
  started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_saga_open ON saga (status, updated_at) WHERE status IN ('RUNNING','WAITING');
CREATE INDEX ix_saga_correlation ON saga (correlation_id);

CREATE TABLE saga_step (
  saga_id UUID REFERENCES saga, step TEXT, attempt INT,
  status TEXT, request_ref TEXT, response_ref TEXT, error_code TEXT,
  started_at TIMESTAMPTZ, ended_at TIMESTAMPTZ,
  PRIMARY KEY (saga_id, step, attempt)
);

CREATE TABLE payment (
  payment_id        UUID NOT NULL,
  correlation_id    TEXT NOT NULL,
  customer_id       UUID NOT NULL,
  debit_account     TEXT NOT NULL,
  creditor          JSONB NOT NULL,
  amount            NUMERIC(18,2) NOT NULL,
  currency          CHAR(3) NOT NULL,
  payment_type      TEXT NOT NULL,     -- INTERNAL, FPS, CHAPS, BACS, INBOUND
  status            TEXT NOT NULL,     -- INITIATED, CHECKS_PASSED, HELD, SUBMITTED, ACCEPTED, COMPLETED, REJECTED, FAILED, RETURNED; inbound: RECEIVED, FLAGGED
  essence_order_ref TEXT,
  finastra_ref      TEXT,
  idempotency_key   TEXT NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (payment_id, created_at),
  UNIQUE (idempotency_key, created_at),
  UNIQUE (finastra_ref, created_at)
) PARTITION BY RANGE (created_at);
CREATE TABLE payment_default PARTITION OF payment DEFAULT;
CREATE INDEX ix_payment_customer ON payment (customer_id, created_at DESC);
CREATE INDEX ix_payment_open ON payment (status) WHERE status NOT IN ('COMPLETED','FAILED','RETURNED');
CREATE INDEX ix_payment_correlation ON payment (correlation_id);

CREATE TABLE payment_status_history (
  payment_id UUID, from_status TEXT, to_status TEXT, reason TEXT,
  actor TEXT, correlation_id TEXT, at TIMESTAMPTZ NOT NULL DEFAULT now()
) PARTITION BY RANGE (at);
CREATE TABLE payment_status_history_default PARTITION OF payment_status_history DEFAULT;
CREATE INDEX ix_psh_payment ON payment_status_history (payment_id, at);

CREATE TABLE inbox (
  event_id TEXT PRIMARY KEY, source TEXT NOT NULL, type TEXT NOT NULL,
  payload JSONB NOT NULL, correlation_id TEXT, status TEXT NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(), processed_at TIMESTAMPTZ
);
CREATE TABLE outbox (
  id BIGSERIAL PRIMARY KEY, aggregate_id UUID NOT NULL, topic TEXT NOT NULL,
  event_type TEXT NOT NULL, payload JSONB NOT NULL, headers JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
