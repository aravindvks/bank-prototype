# Banking Platform Prototype

A UK real-time web bank on Oracle Cloud Infrastructure that integrates three vendor sandboxes:

| Vendor | Role | Our component |
| --- | --- | --- |
| Finastra Fusion Essence | Core banking and UK payments (FPS, CHAPS, Bacs routing) | `essence-adapter` |
| Thredd | Card issuing and processing, real-time authorisations over EHI | `thredd-adapter`, `ehi-responder` |
| Atfinity | KYC and KYB | `atfinity-adapter` |

The design is in the HLD and LLD documents ("Banking Platform Prototype – High-Level Design" and "– Low-Level Design"). This repository follows the LLD's names, contracts, topics, tables and error codes.

## Status: Phase 1 (Priya end to end)

| Phase | Scope | Status |
| --- | --- | --- |
| 0 | Build, local stack, schemas, topics, service skeletons, simulators, one same-bank payment and one card tap end to end | Done |
| 1 | Priya end to end: onboarding and KYC saga, card issue, EHI with cache, holds, limits and settlement, sanctions freeze, outbound UK payments with scheme status, inbound payments, customer web app; simulator and sandbox connectors per vendor (`vendor.<name>.mode`) | **Done** |
| 2 | Staff portal (review queue, clear or confirm, journey search), card controls, live push (Server-Sent Events), reconciliation matching, transactional outbox relayed by Debezium, login with roles (Keycloak standing in for Identity Domains) | **Done** |
| 3 | OCI Terraform (network with reserved egress IP, OKE, PostgreSQL, Streaming topics, Cache, Vault, Object Storage with retention lock, OCIR, API Gateway, WAF, logging, APM), Helm chart (LLD section 13 sizing, dedicated EHI load balancer, Debezium Connect, Vault-synced secrets) and the release pipeline | **Built and validated; not yet applied** |
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
make smoke    # end-to-end check of the whole journey (19 steps)
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
17. Reconciliation: payments and card settlements match Essence with zero exceptions; a rogue posting is reported
18. Outbox relay: the Debezium connector is running after carrying every event above
19. Login: no token → 401; customer on the staff API → 403; view-only staff cannot run reconciliation; Asha's login linked at sign-up

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
```

## Conventions (LLD section 1)

- **Correlation ID** `COR-<ONB|PAY|CRD|TXN|CUS|REC>-<12 base32>` on every call (`X-Correlation-ID`), Kafka record (`correlationId` header) and log line (`[COR-…]`). The first service of a journey creates it.
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
- `mapping_audit` is listed in the table set but had no DDL; it is defined in `services/mapping-service/src/main/resources/db/migration/V1__mapping_schema.sql`.
