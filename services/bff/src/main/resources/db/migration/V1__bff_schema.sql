-- BFF schema (LLD section 10).

CREATE TABLE payee (
  payee_id        UUID PRIMARY KEY,
  customer_id     UUID NOT NULL,
  name            TEXT NOT NULL,
  sort_code       CHAR(6) NOT NULL,
  account_number  CHAR(8) NOT NULL,
  reference       TEXT,
  cop_result      TEXT,            -- MATCH, CLOSE_MATCH, NO_MATCH, UNAVAILABLE
  cop_checked_at  TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (customer_id, sort_code, account_number)
);

CREATE TABLE idempotency_key (
  key TEXT PRIMARY KEY, request_hash TEXT NOT NULL,
  response JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

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
