-- Observability (LLD section 17): the producer's W3C traceparent rides with the event. Debezium copies
-- this column, and created_at, into the Kafka record headers traceparent and occurredAt.
ALTER TABLE outbox ADD COLUMN traceparent TEXT NOT NULL DEFAULT '';
