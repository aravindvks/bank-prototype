-- Credit cards on Thredd credit processing (LLD section 3, "Credit cards"). Thredd holds the credit
-- account (balance, open-to-buy, interest, statements); these tables record the bank's side of each
-- journey: the application and its decision, the account's status with the bank, statements as they
-- fall due, and repayments from the customer's current account.

CREATE TABLE credit_application (
  application_id      UUID PRIMARY KEY,
  customer_id         UUID NOT NULL,
  correlation_id      TEXT NOT NULL,
  status              TEXT NOT NULL,   -- RECEIVED, REFERRED, OFFERED, OPEN, DECLINED, EXPIRED
  name                TEXT,
  annual_income       NUMERIC(18,2) NOT NULL,
  employment_status   TEXT NOT NULL,
  monthly_housing     NUMERIC(18,2) NOT NULL,
  monthly_commitments NUMERIC(18,2) NOT NULL,
  requested_limit     NUMERIC(18,2) NOT NULL,
  decision_id         TEXT,            -- decision engine reference
  bureau_ref          TEXT,            -- credit bureau soft-search reference
  outcome             TEXT,            -- APPROVE, REFER, DECLINE (engine), then the underwriter's decision
  reasons             TEXT,            -- comma-separated reason codes, kept for audit and the customer's letter
  offered_limit       NUMERIC(18,2),
  purchase_apr        NUMERIC(5,2),
  reviewer            TEXT,
  account_id          TEXT,            -- Thredd credit account once opened
  created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_credit_application_customer ON credit_application (customer_id, created_at DESC);
CREATE INDEX ix_credit_application_open ON credit_application (status) WHERE status IN ('RECEIVED','REFERRED','OFFERED');

CREATE TABLE credit_account (
  account_id        TEXT PRIMARY KEY, -- Thredd credit account
  application_id    UUID NOT NULL,
  customer_id       UUID NOT NULL,
  card_token        TEXT NOT NULL,
  card_last4        TEXT NOT NULL,
  credit_limit      NUMERIC(18,2) NOT NULL,
  purchase_apr      NUMERIC(5,2) NOT NULL,
  status            TEXT NOT NULL,      -- ACTIVE, ARREARS, WITHDRAWN, CLOSED
  arrears_count     INT NOT NULL DEFAULT 0,
  cooling_off_until TIMESTAMPTZ NOT NULL, -- 14-day right to withdraw (Consumer Credit Act s66A)
  correlation_id    TEXT NOT NULL,
  opened_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_credit_account_customer ON credit_account (customer_id);

CREATE TABLE credit_statement (
  statement_id    TEXT PRIMARY KEY,     -- Thredd statement
  account_id      TEXT NOT NULL REFERENCES credit_account,
  statement_date  DATE NOT NULL,
  balance         NUMERIC(18,2) NOT NULL,
  minimum_payment NUMERIC(18,2) NOT NULL,
  interest        NUMERIC(18,2) NOT NULL DEFAULT 0,
  due_date        DATE NOT NULL,
  due_at          TIMESTAMPTZ NOT NULL,
  status          TEXT NOT NULL,        -- ISSUED, PAID_IN_FULL, MINIMUM_PAID, MISSED
  late_fee        NUMERIC(18,2),
  correlation_id  TEXT NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  settled_at      TIMESTAMPTZ
);
CREATE INDEX ix_credit_statement_account ON credit_statement (account_id, statement_date DESC);

CREATE TABLE credit_repayment (
  repayment_id   UUID PRIMARY KEY,
  account_id     TEXT NOT NULL,
  customer_id    UUID NOT NULL,
  debit_account  TEXT NOT NULL,         -- the customer's Essence current account
  amount         NUMERIC(18,2) NOT NULL,
  status         TEXT NOT NULL,         -- INITIATED, DEBITED, COMPLETED, FAILED, REFUNDED
  reason         TEXT,
  essence_ref    TEXT,
  thredd_ref     TEXT,
  correlation_id TEXT NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_credit_repayment_account ON credit_repayment (account_id, created_at DESC);
