#!/bin/bash
# Registers the Debezium outbox connector (LLD section 9) once Kafka Connect is up.
set -euo pipefail
URL=${CONNECT_URL:-http://connect:8083}
for i in $(seq 1 90); do curl -sf "$URL/connectors" >/dev/null && break; sleep 2; done
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
for i in $(seq 1 60); do
  s=$(curl -sf "$URL/connectors/pbp-outbox/status" || true)
  echo "$s" | grep -q '"state":"RUNNING"' && ! echo "$s" | grep -q FAILED && { echo "connector running: $s"; exit 0; }
  sleep 2
done
echo "connector not running: $s"; exit 1
