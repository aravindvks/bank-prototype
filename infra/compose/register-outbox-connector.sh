#!/bin/bash
# Registers the Debezium outbox connector (LLD section 9) once Kafka Connect is up.
set -euo pipefail
URL=${CONNECT_URL:-http://connect:8083}
# Kafka Connect can take a few minutes on a laptop the first time (plugin scan). Wait up to 5 minutes.
for i in $(seq 1 150); do curl -sf "$URL/connectors" >/dev/null && break; sleep 2; done
curl -sf "$URL/connectors" >/dev/null || { echo "Kafka Connect never answered at $URL. Check: docker compose logs connect"; exit 1; }
curl -sf -X PUT "$URL/connectors/pbp-outbox/config" -H 'Content-Type: application/json' -d '{
  "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
  "plugin.name": "pgoutput",
  "database.hostname": "postgres",
  "database.port": "5432",
  "database.user": "pbp",
  "database.password": "pbp",
  "database.dbname": "pbp",
  "topic.prefix": "pbp",
  "slot.name": "pbp_outbox",
  "publication.name": "pbp_outbox",
  "publication.autocreate.mode": "all_tables",
  "table.include.list": "bff.outbox,orchestration.outbox,mapping.outbox,ehi.outbox",
  "snapshot.mode": "no_data",
  "tombstones.on.delete": "false",
  "key.converter": "org.apache.kafka.connect.storage.StringConverter",
  "value.converter": "org.apache.kafka.connect.storage.StringConverter",
  "header.converter": "org.apache.kafka.connect.storage.StringConverter",
  "transforms": "outbox",
  "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
  "transforms.outbox.table.field.event.id": "event_id",
  "transforms.outbox.table.field.event.key": "aggregate_id",
  "transforms.outbox.table.field.event.payload": "payload",
  "transforms.outbox.route.by.field": "topic",
  "transforms.outbox.route.topic.replacement": "${routedByValue}",
  "transforms.outbox.table.fields.additional.placement": "event_type:header:type,correlation_id:header:correlationId,event_id:header:eventId,traceparent:header:traceparent,created_at:header:occurredAt",
  "transforms.outbox.table.expand.json.payload": "false"
}'
echo

# Self-healing for the usual local failures, then a clear reason if it still will not run:
# - the task fails once because Postgres still holds the replication slot for a worker that just died
#   ("replication slot ... is active"): restarting the task a few seconds later fixes it;
# - Connect's stored offset points at WAL Postgres no longer has (volumes reset or kept out of step):
#   clear the connector's offsets (Connect 3.6+ offsets API) so it starts from the slot. Safe here:
#   outbox rows are deleted once published, and snapshot.mode is no_data.
status() { curl -sf "$URL/connectors/pbp-outbox/status" || true; }
restarts=0; reset=0
for i in $(seq 1 90); do
  s=$(status)
  if echo "$s" | grep -q '"state":"RUNNING"' && ! echo "$s" | grep -q '"state":"FAILED"'; then
    echo "connector running: $s"; exit 0
  fi
  if echo "$s" | grep -q '"state":"FAILED"'; then
    echo "connector task FAILED: $(echo "$s" | grep -o '"trace":"[^"]\{0,400\}' | head -1)"
    if [ $reset = 0 ] && echo "$s" | grep -qiE 'no longer available|confirmed_flush|lsn|offset'; then
      echo "resetting the connector's stored offsets (stale WAL position)"
      curl -sf -X PUT "$URL/connectors/pbp-outbox/stop" >/dev/null || true
      sleep 3
      curl -sf -X DELETE "$URL/connectors/pbp-outbox/offsets" >/dev/null || echo "offset reset refused"
      curl -sf -X PUT "$URL/connectors/pbp-outbox/resume" >/dev/null || true
      reset=1; sleep 5; continue
    fi
    if [ $restarts -lt 3 ]; then
      restarts=$((restarts + 1)); echo "restarting the connector (attempt $restarts of 3)"
      sleep 5
      curl -sf -X POST "$URL/connectors/pbp-outbox/restart?includeTasks=true&onlyFailed=true" >/dev/null || true
      sleep 5; continue
    fi
    break
  fi
  sleep 2
done
echo "connector not running: $s"
echo "Fix: make down && docker volume rm pbp_pgdata (drops the stale replication slot and local data), then make up."
exit 1
