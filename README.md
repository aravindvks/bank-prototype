# Banking Platform Prototype

A UK real-time web bank on Oracle Cloud Infrastructure that integrates three vendor sandboxes:

| Vendor | Role | Our component |
| --- | --- | --- |
| Finastra Fusion Essence | Core banking and UK payments (FPS, CHAPS, Bacs routing) | `essence-adapter` |
| Thredd | Card issuing and processing, real-time authorisations over EHI | `thredd-adapter`, `ehi-responder` |
| Atfinity | KYC and KYB | `atfinity-adapter` |

The design is in the HLD and LLD documents ("Banking Platform Prototype – High-Level Design" and "– Low-Level Design"). This repository follows the LLD's names, contracts, topics, tables and error codes.

## Status

| Phase | Scope | Status |
| --- | --- | --- |
| 0 | Build, local stack, schemas, topics, service skeletons, simulators, one same-bank payment and one card tap end to end | Done |
| 1 | Priya end to end: onboarding and KYC saga, card issue, EHI with cache, holds, limits and settlement, sanctions freeze, outbound UK payments with scheme status, inbound payments, customer web app; simulator and sandbox connectors per vendor (`vendor.<name>.mode`) | **Done** |
| 2 | Staff portal (review queue, clear or confirm, journey search), card controls, live push (Server-Sent Events), reconciliation matching, transactional outbox relayed by Debezium, login with roles (Keycloak standing in for Identity Domains) | **Done** |
| 3 | OCI Terraform (network with reserved egress IP, OKE, PostgreSQL, Streaming topics, Cache, Vault, Object Storage with retention lock, OCIR, API Gateway, WAF, logging, APM), Helm chart (LLD section 13 sizing, dedicated EHI load balancer, Debezium Connect, Vault-synced secrets) and the release pipeline | **Built and validated; not yet applied** |
| 2b | More UK payment types: Confirmation of Payee, standing orders and scheduled payments, Direct Debits (Bacs, with returns and the Direct Debit Guarantee), Bacs Direct Credits, CHAPS (hours, high-value approval, fee) | **Done** (simulator) |
| 2c | Observability for every flow: traces across HTTP, Kafka (through the outbox) and Temporal; flow metrics and "stuck" gauges from the orchestration tables; dashboards per flow; alert rules; logs in Loki linked to traces; smoke checks that a payment and a card tap are each one trace | **Done** locally; OCI wiring written, not applied |
| 4 | Switch each adapter from simulator to vendor sandbox as credentials arrive | |

## Deploying to OCI (Phase 3)

Validated in CI (`terraform validate`, `helm lint`, `kubeconform`) but not yet applied to a tenancy.

1. **Infrastructure** (`infra/terraform`): copy `terraform.tfvars.example` and `backend.hcl.example`, then
   `terraform init -backend-config=backend.hcl && terraform apply`. Give the `nat_egress_ip` output to
   Finastra, Thredd and Atfinity for their allow-lists.
2. **Cluster add-ons** (once per cluster): Temporal (official Temporal Helm chart, namespace `temporal`,
   persistence on the same PostgreSQL), External Secrets Operator with an OCI Vault `ClusterSecretStore`,
   and the `ocir` image pull secret and `ehi-tls` certificate secret.
3. **Secrets in Vault**: `pbp-<env>-db-user`, `-db-password`, `-webhook-secret`,
   `-spring-kafka-properties-sasl-jaas-config` (OCI Streaming auth token).
4. **Release**: run the `deploy` workflow (Actions → deploy → environment). It builds and tests, pushes all
   images to OCIR tagged with the commit, and runs `helm upgrade --install pbp-<env>`.
5. **After the first deploy**: set `bff_backend_url` (the web app's private load balancer) and
   `ehi_load_balancer_id` (the `ehi-public` load balancer) in Terraform and apply again to wire API Gateway
   and WAF.

## Continuous integration

Every push runs three GitHub Actions jobs: **build** (compile and unit tests, including the Temporal workflows on Temporal's test server), **infra** (Terraform and Helm validation) and **e2e** (starts the whole stack in Docker and runs `scripts/smoke.sh`). The **deploy** workflow is run by hand per environment. Failures are reported as annotations on the commit.

## Run it

Prerequisites: JDK 21, Docker Desktop with Compose v2, `make`. Gradle 8.10 must run on Java 21 or older: if your default `java` is newer (for example 25, which fails with "Unsupported class file major version 69"), `make` picks up an installed Java 21 automatically; without make, set `JAVA_HOME` to Java 21 first.

```bash
make up       # builds all jars, starts infrastructure + services + vendor simulator
make smoke    # end-to-end check of every flow, including traces, metrics and logs (27 steps)
make down     # stop (keeps data);  make clean  removes data too
```

Infrastructure only, for running services from the IDE: `make infra`.

| What | URL |
| --- | --- |
| **Customer web app** | **http://localhost:3000** (sign up, pay, card controls; demo bar on top) |
| **Staff portal** | **http://localhost:3000/#staff** (reviews, held payments, journey search) |
| BFF (customer API) | http://localhost:8080 |
| Orchestrator | http://localhost:8081 |
| Mapping service | http://localhost:8082 |
| EHI responder | http://localhost:8083 |
| Essence / Thredd / Atfinity adapters | :8084 / :8085 / :8086 |
| Recon service | http://localhost:8087 |
| Vendor simulator | http://localhost:8090 |
| Temporal UI | http://localhost:8233 (namespace `pbp-local`) |
| **Grafana** | **http://localhost:3001** (dashboards open without login; admin/admin to edit) |
| Prometheus · Tempo · Loki | http://localhost:9090 · :3200 · :3100 |
| PostgreSQL | localhost:5432, db `pbp`, user/password `pbp` |
| Kafka | localhost:9092 |

## What the smoke test proves

1. Priya's master customer record linked to her Essence party and account
2. Card tap approved through the EHI responder with an Essence hold
3. Same-bank payment Priya → Ravi through BFF → Kafka → Temporal → Essence, with idempotent retry
4. Asha signs up: mapping → Atfinity case → KYC approved by webhook → Essence party and account → Thredd card → ACTIVE
5. A sign-up named "Reject …" ends REJECTED
6. £100 arrives by Faster Payment; Asha taps her new card (Thredd simulator → EHI responder)
7. The cafe settles the tap: presentment captures the hold
8. Declines with reasons: INSUFFICIENT_FUNDS and LIMIT_EXCEEDED (daily limit £300)
9. Sanctions hit: Atfinity rescreening freezes Asha within seconds; her next tap is CUSTOMER_FROZEN
10. Priya pays another bank: Finastra reports ACCEPTED then COMPLETED
11. A payment to sort code 99-xx-xx is rejected by the scheme (PAY-SCHEME-001)
12. Inbound payments are recorded: COMPLETED for an active customer, FLAGGED once frozen
13. The web app serves and reads the BFF through nginx
14. Staff clears Asha's case: she is ACTIVE, the held £5 is released and her card works again
15. Card controls: online switched off → CHANNEL_BLOCKED; £350 approved at a £1,000 limit; frozen card → CARD_BLOCKED
16. Live push: Priya's incoming payment arrives on her event stream
17. Confirmation of Payee: MATCH, CLOSE_MATCH (with the real name), NO_MATCH and UNAVAILABLE; a payment to a name that does not match is REJECTED (PAY-COP-001); a close match the customer confirmed goes through
18. Standing order: monthly, first payment today, next due a month later, then cancelled; a payment scheduled for tomorrow, made early with the demo "pay now"
19. Bacs Direct Credit: a salary file from ACME LTD is recorded as an inbound Bacs credit
20. Direct Debits: an originator's mandate (AUDDIS) becomes ACTIVE; a £30 collection is paid; one larger than her balance is returned unpaid (ARUDD 0); the £30 is refunded under the Direct Debit Guarantee; Priya cancels the mandate and the next collection is returned (ARUDD 1)
21. CHAPS: £12,000 is HELD for staff approval, appears in the Control Room, is approved, then settles with a £20 fee inside CHAPS hours, or waits QUEUED outside them
22. Reconciliation: payments, fees, Bacs credits, Direct Debits and card settlements match Essence with zero exceptions; a rogue posting is reported
23. Outbox relay: the Debezium connector is running after carrying every event above
24. Tracing: Priya's £25 payment is a single trace across bff, orchestrator and essence-adapter (HTTP → outbox → Debezium → Kafka → Temporal → HTTP)
25. Tracing: a card tap is a single trace from ehi-responder to the Essence hold
26. Metrics, alerts and logs: Prometheus scrapes all 9 services, payment and card flow metrics are recorded, the alert rules are loaded, and Loki finds the payment's log lines by correlation ID
27. Login: no token → 401; customer on the staff API → 403; view-only staff cannot run reconciliation; Asha's login linked at sign-up

## Observability (LLD section 15)

One standard, set once in `libs/platform-common`, so no service wires it itself:

- **Traces**: OpenTelemetry through Micrometer, exported over OTLP to the collector when `OTEL_EXPORTER_OTLP_ENDPOINT` is set. Trace context crosses every hop: HTTP (RestClient), Kafka (producer and listener observations; for the outbox, a `traceparent` column that Debezium copies into a header) and Temporal (OpenTracing interceptors over an OpenTelemetry shim). Every span carries `pbp.correlation_id`, so a correlation ID finds its trace: in Grafana, *Explore → Tempo* `{ span.pbp.correlation_id = "COR-PAY-…" }`.
- **Logs**: `INFO [bff,trace=…,span=…,cid=COR-…] …`. Promtail ships them to Loki; each line links to its trace.
- **Metrics** on `/actuator/prometheus`. The flow metrics are counted from the orchestration tables by `FlowMetricsCollector`, so a payment that silently stops moving still shows: `pbp_payment_transitions_total`, `pbp_payment_completion_seconds`, `pbp_payments_open` and `pbp_payments_oldest_seconds` (time in the current status), `pbp_sagas_*`, `pbp_schedules_*`, `pbp_dd_mandates`. Plus `pbp_ehi_decision_seconds` (the EHI time budget), `pbp_cop_results_total`, `pbp_events_published_total` / `consumed_total`, `pbp_events_delivery_seconds` (outbox to listener), `pbp_recon_*`, HTTP server and vendor-call timers, Temporal SDK metrics and Kafka consumer lag. Labels are codes and statuses only: never customer, payment or correlation IDs.
- **Dashboards** (folder *Banking Platform Prototype*): Overview, Payments, Cards and EHI, Standing orders and Direct Debits, Onboarding and KYC, Reconciliation, Platform. Generated by `make dashboards` from `infra/observability/grafana/generate_dashboards.py`; edit that, not the JSON.
- **Alerts** in `infra/observability/prometheus/alerts.yml` (shown in Grafana under Alerting): EHI over budget or CORE_UNAVAILABLE, payment failure rate, FPS stuck > 5 min, CHAPS stuck or still QUEUED in CHAPS hours, HELD > 48 h, CoP UNAVAILABLE rising, standing order missed, Direct Debit not settled, onboarding stuck, recon exceptions or not run, service down, 5xx, vendor errors, slow event delivery, consumer lag, dead letters, Temporal activity failures. No Alertmanager locally; on OCI, route them from kube-prometheus-stack.
- **OCI**: the same stack from upstream charts (kube-prometheus-stack, Tempo, Loki, OpenTelemetry Collector). `deploy/helm/pbp` adds the PodMonitor, PrometheusRule and dashboard ConfigMaps when `observability.enabled=true`; traces are sampled at 10% (`PBP_TRACE_SAMPLING`). The collector can also export to OCI APM.

## Sign-in (local)

Keycloak (http://localhost:8180, admin / admin) stands in for OCI Identity Domains. Realm `pbp`:

| User | Password | Can do |
| --- | --- | --- |
| priya | priya123 | Customer (linked by `make smoke`) |
| asha | asha123 | Customer (linked when her sign-up completes) |
| aravind | staff123 | Staff portal with the compliance role: clear or confirm reviews, run reconciliation |
| ops | ops123 | Staff portal, view only |

New customers can register on the Keycloak sign-in page and then open an account in the app; the login is
linked to the new bank customer when onboarding completes. Running the BFF from the IDE without Keycloak uses
`pbp.auth.enabled=false` and the development headers `X-Customer-Id` / `X-Staff-User`.

## Simulator rules (for demos)

| To show | Do this |
| --- | --- |
| KYC rejected or referred | Sign up with a name containing "Reject" or "Refer" |
| Scheme rejection | Pay sort code 99-xx-xx |
| Slow, timed-out or failing vendor | Send header `X-Sim-Scenario: slow`, `timeout` or `error` to the simulator |
| Money arriving | Demo bar "Receive £20", or `POST :8090/essence/v1/sim/inbound` |
| Card purchase / settlement | Demo bar "Tap card", or `POST :8090/thredd/v1/sim/card-tap` and `/sim/settle` |
| Sanctions hit | Demo bar "Sanctions hit", or `POST :8090/atfinity/v1/sim/cases/{caseId}/rescreen-hit` |
| Confirmation of Payee outcomes | Same bank: the real holder's name matches; "R Kumar" for Ravi Kumar is a close match; another name is no match. Sort code 98-xx-xx: unavailable. Account 00000000: no match. Other banks match unless listed in the simulator's CoP directory (040004 12345678 Arjun Mehta, 309634 55667788 Northwind Lettings Ltd) |
| High-value approval | Pay £10,000 or more; approve or reject it in the staff portal |
| CHAPS hours | Choose CHAPS: 06:00–17:30 UK time on working days, otherwise QUEUED until the next opening (`CHAPS_OPENS`, `CHAPS_CUT_OFF`, `CHAPS_HOLIDAYS`) |
| Salary by Bacs | Demo bar "Salary (Bacs)", or `POST :8090/essence/v1/sim/bacs/credits` |
| Direct Debits | Demo bar "New Direct Debit" then "Collect Direct Debit", or `POST :8090/essence/v1/sim/dd/mandates` and `/sim/dd/collections` (optional `dueDate`; a collection waits for its due date at `DD_ENTRY_TIME`, 06:00 UK by default and 00:00 in Docker Compose so demos settle at once) |
| Standing order now | "Pay next now" beside a standing order (demo only; signals the schedule's workflow) |

## Layout

```
libs/platform-common      correlation IDs, problem+json errors, event envelope, Kafka publisher
services/bff              POST /v1/payments → payment-commands (Idempotency-Key, 202 Accepted)
services/orchestrator     Temporal payment saga (task queue "payments"), Kafka command listener
services/mapping-service  master customer ID ↔ Atfinity / Essence / Thredd IDs, audit, mapping-events
services/ehi-responder    POST /ehi/v1/messages: card → customer → status → Essence balance → decision
services/essence-adapter  balance (standard and EHI lanes), same-bank transfer
services/thredd-adapter   card issue (token only, PAN pattern guard)
services/atfinity-adapter open KYC/KYB case
services/recon-service    nightly run records (matching rules in Phase 2)
simulators/vendor-simulator  Essence, Thredd and Atfinity stand-ins; signed webhooks; X-Sim-Scenario
web/                      customer web app (React + Vite), served by nginx in Docker
infra/compose             Docker Compose, topic creation, service image
infra/observability       OpenTelemetry Collector, Prometheus and alert rules, Tempo, Promtail, Grafana dashboards
```

## Conventions (LLD section 1)

- **Correlation ID** `COR-<ONB|PAY|CRD|TXN|CUS|REC>-<12 base32>` on every call (`X-Correlation-ID`), Kafka record (`correlationId` header) and log line (`cid=COR-…`, next to the trace and span IDs). The first service of a journey creates it.
- **IDs** UUID v7. **Money** decimal strings with currency, never floating point. **Time** UTC ISO 8601.
- **Errors** RFC 9457 problem+json with `code`, `correlationId`, `retryable`; codes from LLD section 14.
- **Events** one envelope on every topic, keyed by customer ID.
- **Schemas** one PostgreSQL schema per service (`bff`, `orchestration`, `mapping`, `ehi`, `recon`), migrated by Flyway on start.

## Vendor connectors

Each adapter has one gateway interface and two connectors, chosen by `vendor.<name>.mode`:
`simulator` (default) calls the vendor simulator; `sandbox` is the real vendor connector. The sandbox
connectors fail with a clear `VND-UNAVAIL-001` until each vendor's API specification arrives; then only
that class is written. Nothing outside the adapter changes.

## Shortcuts still open (closed in later phases)

- Events are published directly to Kafka inside the request; the transactional outbox with Debezium replaces this in Phase 2. The `outbox` and `inbox` tables already exist.
- The BFF takes the customer from an `X-Customer-Id` header; Identity Domains login replaces it.
- Webhooks are verified and published directly by the adapters (no database); consumers rely on idempotent IDs rather than the inbox table.
- The BFF validates token signatures against Keycloak's keys but not the issuer, because the browser and the containers reach Keycloak by different host names locally.
- Vendor field names are placeholders until each vendor's API specification is received.

## Corrections to the LLD found while building

- OCI Streaming keeps messages for at most 7 days, so the LLD's 30-day retention for event topics is set to 7 days; longer history comes from the archive bucket and the database.
- Identity Domains carries roles as group names in the `groups` claim; create groups `staff` and `compliance`. Keycloak locally uses realm roles.

- **Partitioned tables need the partition column in every unique key.** PostgreSQL rejects `payment_id UUID PRIMARY KEY` on a table partitioned by `created_at`. `payment` uses `PRIMARY KEY (payment_id, created_at)` and `UNIQUE (idempotency_key, created_at)`; the orchestrator always passes the command's `initiatedAt` as `created_at`, so retries stay idempotent. The LLD DDL should be updated to match.
- The LLD calls the correlation suffix base32, but its example `COR-PAY-7K2M9QXA4B1D` contains `9` and `1`. New IDs use base32; validation accepts `A-Z0-9` so both forms work.
- **Tables without a primary key need a replica identity.** The Debezium publication covers all tables, and PostgreSQL then refuses UPDATE or DELETE on a published table with no replica identity. `recon_match` and `recon_exception` partitions use `REPLICA IDENTITY FULL` (recon `V2__replica_identity.sql`); pg_partman's monthly partitions in OCI need the same.
- **More UK payment types (not in the LLD yet).** Added `payment_schedule` and `dd_mandate` tables and `cop_result`, `fee`, `schedule_id` columns on `payment` (orchestration `V3__uk_payment_types.sql`); payment type `DIRECT_DEBIT`; statuses `QUEUED`, `PRESENTED`, `REFUNDED`; endpoints `/v1/payees`, `/v1/schedules`, `/v1/direct-debits`, `/v1/staff/payments/{id}/decision`; commands `schedule.*` and `directdebit.*` on `payment-commands`; events `directdebit.*` and `schedule.*` on `payment-events`; error code `PAY-DD-002`. The HLD and LLD have been updated to match.
- **The orchestrator chooses FPS or CHAPS**, not Finastra: the customer picks CHAPS for large same-day payments, and the bank's CHAPS hours, fee and limits are its tariff. The LLD said Finastra routes to the scheme.
- **Confirmation of Payee is checked twice**: the app checks before the customer pays, and the payment saga checks again, so a client cannot skip it by sending `copResult: MATCH`. UNAVAILABLE goes ahead (bank policy left open in the LLD).
- `mapping_audit` is listed in the table set but had no DDL; it is defined in `services/mapping-service/src/main/resources/db/migration/V1__mapping_schema.sql`.
- **Observability as built differs from LLD section 15** (the LLD now has an "As built" part). Metric names follow Micrometer (`pbp_*`, `http_*_requests_seconds`) instead of the planned `ehi_decision_latency_ms` and friends; logs are plain text with trace and correlation IDs rather than JSON. Every outbox table has a `traceparent` column, and the Debezium connector copies `traceparent` and `created_at` into the Kafka headers `traceparent` and `occurredAt`. The orchestration schema adds `flow_metrics_watermark` (orchestration `V5__flow_metrics_watermark.sql`), so replicas count each status change once. The LLD's log format `[COR-…]` becomes `[service,trace=…,span=…,cid=COR-…]`.
