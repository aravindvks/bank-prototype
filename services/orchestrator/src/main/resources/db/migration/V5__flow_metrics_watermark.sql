-- Observability (LLD section 15): how far FlowMetricsCollector has counted payment_status_history.
-- One row per source; replicas take it with FOR UPDATE SKIP LOCKED so each transition is counted once.
CREATE TABLE flow_metrics_watermark (
  name TEXT PRIMARY KEY,
  at   TIMESTAMPTZ NOT NULL
);
INSERT INTO flow_metrics_watermark (name, at) VALUES ('payment_status_history', now());
