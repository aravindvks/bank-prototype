-- Outbox relayed by Debezium (LLD section 9). Rows are inserted and deleted in the same transaction:
-- Debezium reads the insert from the write-ahead log, so the table stays empty.
ALTER TABLE outbox ALTER COLUMN aggregate_id TYPE TEXT;
ALTER TABLE outbox ALTER COLUMN aggregate_id DROP NOT NULL;
ALTER TABLE outbox ADD COLUMN event_id TEXT;
ALTER TABLE outbox ADD COLUMN correlation_id TEXT;
