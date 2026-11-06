#!/usr/bin/env bash
# End-to-end smoke test for Phase 0, using Priya and Ravi from the HLD flows.
# Needs: make up (all services running). Uses only curl, grep and sed.
set -euo pipefail

MAPPING=${MAPPING:-http://localhost:8082}
BFF=${BFF:-http://localhost:8080}
EHI=${EHI:-http://localhost:8083}
SIM=${SIM:-http://localhost:8090}

pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; exit 1; }
field() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | sed "s/\"$1\":\"\(.*\)\"/\1/"; }
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen | tr 'A-Z' 'a-z'; }

echo "1. Priya's customer record and vendor links (mapping service)"
existing=$(curl -s "$MAPPING/v1/resolve?system=ESSENCE&type=ACCOUNT&id=ACC-5501" || true)
if echo "$existing" | grep -q '"customerId"'; then
  CUSTOMER=$(echo "$existing" | field customerId)
  ACCOUNT_REF=$(echo "$existing" | sed 's/.*"ref":{"refId":"\([^"]*\)".*/\1/')
  pass "Priya already linked: customer $CUSTOMER"
else
  CUSTOMER=$(curl -sf -X POST "$MAPPING/v1/customers" -H 'Content-Type: application/json' \
    -d '{"customerType":"INDIVIDUAL"}' | field customerId)
  PARTY_REF=$(curl -sf -X POST "$MAPPING/v1/customers/$CUSTOMER/refs" -H 'Content-Type: application/json' \
    -d '{"system":"ESSENCE","entityType":"PARTY","externalId":"ESS-PTY-88231"}' | field refId)
  ACCOUNT_REF=$(curl -sf -X POST "$MAPPING/v1/customers/$CUSTOMER/refs" -H 'Content-Type: application/json' \
    -d "{\"system\":\"ESSENCE\",\"entityType\":\"ACCOUNT\",\"externalId\":\"ACC-5501\",\"parentRefId\":\"$PARTY_REF\"}" | field refId)
  curl -sf -X PATCH "$MAPPING/v1/customers/$CUSTOMER/status" -H 'Content-Type: application/json' \
    -d '{"status":"ACTIVE","reason":"KYC approved (smoke test)"}' >/dev/null
  pass "Created customer $CUSTOMER with party and account links, status ACTIVE"
fi

TOKEN="thr_tok_smoke$(date +%s)"
curl -sf -X POST "$MAPPING/v1/customers/$CUSTOMER/refs" -H 'Content-Type: application/json' \
  -d "{\"system\":\"THREDD\",\"entityType\":\"CARD\",\"externalId\":\"$TOKEN\",\"parentRefId\":\"$ACCOUNT_REF\"}" >/dev/null
pass "Linked card token $TOKEN to account ACC-5501"

echo "2. Card tap: £3.50 at a cafe (EHI responder)"
auth=$(curl -sf -X POST "$EHI/ehi/v1/messages" -H 'Content-Type: application/json' \
  -d "{\"messageId\":\"EHI-$(uuid)\",\"messageType\":\"AUTHORISATION\",\"cardToken\":\"$TOKEN\",\"amount\":\"3.50\",\"currency\":\"GBP\",\"merchant\":{\"name\":\"Bean There Cafe\",\"mcc\":\"5814\",\"channel\":\"contactless\"}}")
echo "$auth" | grep -q '"decision":"APPROVE"' && pass "Approved: $auth" || fail "Expected APPROVE, got $auth"

echo "3. Same-bank payment: Priya pays Ravi £25.00 (BFF → Kafka → Temporal → Essence adapter)"
before=$(curl -sf "$SIM/essence/v1/accounts/ACC-5510/balance" | field available)
KEY=$(uuid)
BODY='{"debtorAccount":"ACC-5501","creditor":{"name":"Ravi Kumar","sortCode":"200001","accountNumber":"41550198"},"amount":{"value":"25.00","currency":"GBP"},"purpose":"OTHER","remittanceInfo":"Smoke test","copResult":"MATCH"}'
resp=$(curl -sf -X POST "$BFF/v1/payments" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -H "X-Customer-Id: $CUSTOMER" -d "$BODY")
PAYMENT=$(echo "$resp" | field paymentId)
CORR=$(echo "$resp" | field correlationId)
[ -n "$PAYMENT" ] && pass "202 Accepted: payment $PAYMENT, correlation $CORR" || fail "No paymentId in $resp"

replay=$(curl -sf -X POST "$BFF/v1/payments" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -H "X-Customer-Id: $CUSTOMER" -d "$BODY")
[ "$(echo "$replay" | field paymentId)" = "$PAYMENT" ] && pass "Retry with the same Idempotency-Key returned the same payment" \
  || fail "Idempotency replay returned $replay"

expected=$(awk "BEGIN { printf \"%.2f\", $before + 25 }")
for _ in $(seq 1 20); do
  after=$(curl -sf "$SIM/essence/v1/accounts/ACC-5510/balance" | field available)
  [ "$after" = "$expected" ] && break
  sleep 1
done
[ "$after" = "$expected" ] && pass "Ravi's Essence balance moved $before → $after" \
  || fail "Ravi's balance is $after, expected $expected. Check the workflow in Temporal UI (http://localhost:8233)"

echo
echo "All Phase 0 checks passed. Search the logs for $CORR to follow the payment across services."
