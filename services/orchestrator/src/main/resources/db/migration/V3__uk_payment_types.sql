-- More UK payment types (LLD section 7): Confirmation of Payee, CHAPS fees, standing orders and
-- scheduled payments, Direct Debits (Bacs). Bacs Direct Credits reuse the INBOUND payment row.

-- payment_type gains DIRECT_DEBIT; status gains QUEUED (outside CHAPS hours), PRESENTED (Direct
-- Debit waiting for its due date) and REFUNDED (Direct Debit Guarantee refund).
ALTER TABLE payment ADD COLUMN cop_result  TEXT;            -- MATCH, CLOSE_MATCH, NO_MATCH, UNAVAILABLE
ALTER TABLE payment ADD COLUMN fee         NUMERIC(18,2);   -- tariff charge, e.g. the CHAPS fee
ALTER TABLE payment ADD COLUMN schedule_id UUID;            -- set when a standing order or scheduled payment made it

CREATE TABLE payment_schedule (
  schedule_id      UUID PRIMARY KEY,
  customer_id      UUID NOT NULL,
  correlation_id   TEXT NOT NULL,
  kind             TEXT NOT NULL,        -- ONE_OFF (pay on a date) or STANDING_ORDER
  frequency        TEXT,                 -- WEEKLY or MONTHLY for standing orders
  debit_account    TEXT NOT NULL,
  creditor         JSONB NOT NULL,
  amount           NUMERIC(18,2) NOT NULL,
  currency         CHAR(3) NOT NULL,
  reference        TEXT,
  scheme           TEXT,                 -- FPS or CHAPS for other banks
  cop_result       TEXT,
  start_date       DATE NOT NULL,
  end_date         DATE,
  next_date        DATE,
  payments_made    INT NOT NULL DEFAULT 0,
  last_payment_id  UUID,
  last_status      TEXT,
  status           TEXT NOT NULL,        -- ACTIVE, CANCELLED, COMPLETED
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_schedule_customer ON payment_schedule (customer_id, created_at DESC);

CREATE TABLE dd_mandate (
  mandate_id          UUID PRIMARY KEY,
  mandate_ref         TEXT NOT NULL UNIQUE,  -- Essence / Bacs reference for the Direct Debit Instruction
  customer_id         UUID NOT NULL,
  debit_account       TEXT NOT NULL,
  originator_name     TEXT NOT NULL,
  service_user_number TEXT NOT NULL,         -- the originator's SUN
  payer_reference     TEXT,
  status              TEXT NOT NULL,         -- ACTIVE, CANCELLED
  correlation_id      TEXT NOT NULL,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  cancelled_at        TIMESTAMPTZ,
  cancelled_by        TEXT
);
CREATE INDEX ix_mandate_customer ON dd_mandate (customer_id, created_at DESC);
