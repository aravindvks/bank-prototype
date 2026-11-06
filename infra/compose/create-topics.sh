#!/bin/bash
# Topics from LLD section 9. Locally: replication factor 1. In OCI Streaming: RF 3, min ISR 2.
set -euo pipefail
KT=/opt/kafka/bin/kafka-topics.sh
B=${BOOTSTRAP:-kafka:29092}
DAY=86400000

create() { # name partitions retention-ms cleanup
  $KT --bootstrap-server "$B" --create --if-not-exists --topic "$1" --partitions "$2" \
      --replication-factor 1 --config retention.ms="$3" --config cleanup.policy="$4"
  $KT --bootstrap-server "$B" --create --if-not-exists --topic "$1.DLT" --partitions 3 \
      --replication-factor 1 --config retention.ms=$((30 * DAY))
}

create payment-commands    12 $((7 * DAY))  delete
create onboarding-commands  6 $((7 * DAY))  delete
create card-commands        6 $((7 * DAY))  delete
create customer-commands    6 $((7 * DAY))  delete
create kyc-events           6 $((30 * DAY)) delete
create card-events         12 $((30 * DAY)) delete
create payment-events      12 $((30 * DAY)) delete
create onboarding-events    6 $((30 * DAY)) delete
create mapping-events       6 -1            compact

$KT --bootstrap-server "$B" --list
