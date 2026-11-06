-- Recon schema (LLD sections 7, 8 and 10). Partitioned by run_date; DEFAULT partitions locally.

CREATE TABLE recon_run (
  run_id UUID PRIMARY KEY, source TEXT NOT NULL, run_date DATE NOT NULL,
  lines_total INT, matched INT, exceptions INT, status TEXT NOT NULL,
  report_ref TEXT, started_at TIMESTAMPTZ, ended_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_recon_run ON recon_run (source, run_date);

CREATE TABLE recon_match (
  run_id UUID, source_ref TEXT, target_ref TEXT, amount NUMERIC(18,2),
  currency CHAR(3), correlation_id TEXT, run_date DATE NOT NULL
) PARTITION BY RANGE (run_date);
CREATE TABLE recon_match_default PARTITION OF recon_match DEFAULT;

CREATE TABLE recon_exception (
  exception_id UUID, run_id UUID, type TEXT NOT NULL, source_ref TEXT,
  amount NUMERIC(18,2), owner_queue TEXT NOT NULL, status TEXT NOT NULL,
  resolution TEXT, run_date DATE NOT NULL
) PARTITION BY RANGE (run_date);
CREATE TABLE recon_exception_default PARTITION OF recon_exception DEFAULT;

-- Optional payment message service (LLD section 7): only needed if Finastra delivers
-- reconciliation data as ISO 20022 reports. Kept here so the table set is complete.
CREATE TABLE payment_message (
  message_id UUID PRIMARY KEY, payment_id UUID,   -- null until matched
  direction TEXT NOT NULL, iso_type TEXT NOT NULL, msg_id TEXT NOT NULL,
  end_to_end_id TEXT, match_status TEXT NOT NULL DEFAULT 'UNMATCHED',
  object_storage_ref TEXT NOT NULL, at TIMESTAMPTZ NOT NULL
);
