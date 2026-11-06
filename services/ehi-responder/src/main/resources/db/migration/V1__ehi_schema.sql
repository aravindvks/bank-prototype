-- EHI schema (LLD section 10). Monthly partitions come from pg_partman in OCI;
-- locally a DEFAULT partition holds everything.

CREATE TABLE auth_log (
  auth_id UUID, correlation_id TEXT NOT NULL, thredd_msg_id TEXT NOT NULL,
  msg_type TEXT NOT NULL, card_token TEXT NOT NULL, customer_id UUID,
  account TEXT, amount NUMERIC(18,2), currency CHAR(3), merchant JSONB,
  decision TEXT NOT NULL, reason TEXT, essence_hold_ref TEXT,
  essence_posting_ref TEXT, original_auth_id UUID, latency_ms INT,
  at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (auth_id, at), UNIQUE (thredd_msg_id, at)
) PARTITION BY RANGE (at);
CREATE TABLE auth_log_default PARTITION OF auth_log DEFAULT;
CREATE INDEX ix_auth_card ON auth_log (card_token, at DESC);
CREATE INDEX ix_auth_hold ON auth_log (essence_hold_ref);
CREATE INDEX ix_auth_msg ON auth_log (thredd_msg_id);
CREATE INDEX ix_auth_correlation ON auth_log (correlation_id);

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
