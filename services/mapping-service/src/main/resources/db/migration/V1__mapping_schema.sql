-- Mapping schema (HLD Appendix A, LLD section 4).
-- The mapping service is the only writer; every change is audited.

CREATE TABLE customer (
  customer_id     UUID PRIMARY KEY,
  customer_type   TEXT NOT NULL CHECK (customer_type IN ('INDIVIDUAL','BUSINESS')),
  status          TEXT NOT NULL,      -- ONBOARDING, ACTIVE, FROZEN, REJECTED, CLOSED
  kyc_status      TEXT,               -- PENDING, VALID, EXPIRED, FROZEN
  kyc_valid_until DATE,
  risk_rating     TEXT,               -- LOW, MEDIUM, HIGH
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  version         INT NOT NULL DEFAULT 1
);

CREATE TABLE external_ref (
  ref_id                 UUID PRIMARY KEY,
  customer_id            UUID NOT NULL REFERENCES customer,
  system                 TEXT NOT NULL,   -- ATFINITY, ESSENCE, THREDD
  entity_type            TEXT NOT NULL,   -- KYC_CASE, PARTY, ACCOUNT, CARD
  external_id            TEXT NOT NULL,   -- card TOKEN only, never PAN
  parent_ref_id          UUID REFERENCES external_ref,
  status                 TEXT NOT NULL,   -- PENDING, ACTIVE, INACTIVE, REPLACED
  valid_from             TIMESTAMPTZ NOT NULL DEFAULT now(),
  valid_to               TIMESTAMPTZ,
  replaced_by            UUID REFERENCES external_ref,
  created_correlation_id TEXT,
  metadata               JSONB
);
-- A vendor ID belongs to exactly one customer for all time.
CREATE UNIQUE INDEX ux_ext ON external_ref (system, entity_type, external_id);
CREATE INDEX ix_ext_customer ON external_ref (customer_id);

CREATE TABLE mapping_audit (
  audit_id       BIGSERIAL PRIMARY KEY,
  customer_id    UUID NOT NULL,
  ref_id         UUID,
  action         TEXT NOT NULL,          -- CUSTOMER_CREATED, STATUS_CHANGED, REF_LINKED, ...
  before_state   JSONB,
  after_state    JSONB,
  actor          TEXT NOT NULL,
  correlation_id TEXT,
  at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_customer ON mapping_audit (customer_id, at DESC);
CREATE INDEX ix_audit_correlation ON mapping_audit (correlation_id);

-- Shared pattern, one copy per schema (LLD section 10).
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
