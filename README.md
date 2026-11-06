# Banking Platform Prototype

A UK real-time web bank on Oracle Cloud Infrastructure that integrates three vendor sandboxes:

| Vendor | Role | Our component |
| --- | --- | --- |
| Finastra Fusion Essence | Core banking and UK payments (FPS, CHAPS, Bacs routing) | `essence-adapter` |
| Thredd | Card issuing and processing, real-time authorisations over EHI | `thredd-adapter`, `ehi-responder` |
| Atfinity | KYC and KYB | `atfinity-adapter` |

The design is in the HLD and LLD documents ("Banking Platform Prototype – High-Level Design" and "– Low-Level Design"). This repository follows the LLD's names, contracts, topics, tables and error codes.

## Status: Phase 0 (skeleton)

| Phase | Scope | Status |
| --- | --- | --- |
| 0 | Build, local stack, schemas, topics, service skeletons, simulators, one same-bank payment and one card tap end to end | **This commit** |
| 1 | Priya end to end: onboarding and KYC saga, card issue, EHI with OCI Cache and Essence holds, outbound UK payments, inbound payments, web app | Next |
| 2 | Staff portal, recon matching, observability dashboards, outbox relay with Debezium | |
| 3 | OCI Terraform (OKE, API Gateway, EHI listener, PostgreSQL, Streaming, Vault) and pipelines | |
| 4 | Switch each adapter from simulator to vendor sandbox as credentials arrive | |

## Continuous integration

Every push runs two GitHub Actions jobs: **build** (compile and unit tests, including the Temporal payment workflow on Temporal's test server) and **e2e** (starts the whole stack in Docker and runs `scripts/smoke.sh`). Failures are reported as annotations on the commit.

## Run it

Prerequisites: JDK 21, Docker Desktop with Compose v2, `make`. Gradle 8.10 must run on Java 21 or older: if your default `java` is newer (for example 25, which fails with "Unsupported class file major version 69"), `make` picks up an installed Java 21 automatically; without make, set `JAVA_HOME` to Java 21 first.

```bash
make up       # builds all jars, starts infrastructure + services + vendor simulator
make smoke    # end-to-end check: mapping links, a card tap, a same-bank payment, idempotent replay
make down     # stop (keeps data);  make clean  removes data too
```

Infrastructure only, for running services from the IDE: `make infra`.

| What | URL |
| --- | --- |
| BFF (customer API) | http://localhost:8080 |
| Orchestrator | http://localhost:8081 |
| Mapping service | http://localhost:8082 |
| EHI responder | http://localhost:8083 |
| Essence / Thredd / Atfinity adapters | :8084 / :8085 / :8086 |
| Recon service | http://localhost:8087 |
| Vendor simulator | http://localhost:8090 |
| Temporal UI | http://localhost:8233 (namespace `pbp-local`) |
| PostgreSQL | localhost:5432, db `pbp`, user/password `pbp` |
| Kafka | localhost:9092 |

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
simulators/vendor-simulator  Essence, Thredd and Atfinity stand-ins; X-Sim-Scenario: slow | timeout | error
infra/compose             Docker Compose, topic creation, service image
```

## Conventions (LLD section 1)

- **Correlation ID** `COR-<ONB|PAY|CRD|TXN|CUS|REC>-<12 base32>` on every call (`X-Correlation-ID`), Kafka record (`correlationId` header) and log line (`[COR-…]`). The first service of a journey creates it.
- **IDs** UUID v7. **Money** decimal strings with currency, never floating point. **Time** UTC ISO 8601.
- **Errors** RFC 9457 problem+json with `code`, `correlationId`, `retryable`; codes from LLD section 14.
- **Events** one envelope on every topic, keyed by customer ID.
- **Schemas** one PostgreSQL schema per service (`bff`, `orchestration`, `mapping`, `ehi`, `recon`), migrated by Flyway on start.

## Phase 0 shortcuts (all closed in later phases)

- Events are published directly to Kafka inside the request; the transactional outbox with Debezium replaces this in Phase 2. The `outbox` and `inbox` tables already exist.
- The BFF takes the customer from an `X-Customer-Id` header; Identity Domains login replaces it.
- The EHI responder looks cards up through the mapping service instead of OCI Cache, places no Essence hold, and does not check card limits yet.
- Payments to another bank's sort code are rejected with `PAY-RAIL-000`; Finastra routing arrives in Phase 1.
- Vendor field names are placeholders until each vendor's API specification is received.

## Corrections to the LLD found while building

- **Partitioned tables need the partition column in every unique key.** PostgreSQL rejects `payment_id UUID PRIMARY KEY` on a table partitioned by `created_at`. `payment` uses `PRIMARY KEY (payment_id, created_at)` and `UNIQUE (idempotency_key, created_at)`; the orchestrator always passes the command's `initiatedAt` as `created_at`, so retries stay idempotent. The LLD DDL should be updated to match.
- The LLD calls the correlation suffix base32, but its example `COR-PAY-7K2M9QXA4B1D` contains `9` and `1`. New IDs use base32; validation accepts `A-Z0-9` so both forms work.
- `mapping_audit` is listed in the table set but had no DDL; it is defined in `services/mapping-service/src/main/resources/db/migration/V1__mapping_schema.sql`.
