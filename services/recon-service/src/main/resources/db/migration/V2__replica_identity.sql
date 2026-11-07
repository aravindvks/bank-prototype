-- The Debezium outbox relay creates its publication FOR ALL TABLES, and PostgreSQL then refuses
-- UPDATE or DELETE on a published table without a replica identity. recon_match and
-- recon_exception have no primary key, and a re-run of a date deletes that date's results.
-- Replica identity is set per partition (the DEFAULT partitions here; pg_partman's monthly
-- partitions in OCI must get the same setting).
ALTER TABLE recon_match_default REPLICA IDENTITY FULL;
ALTER TABLE recon_exception_default REPLICA IDENTITY FULL;
