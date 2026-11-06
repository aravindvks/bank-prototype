#!/usr/bin/env bash
# End-to-end smoke test for Phase 0, using Priya and Ravi from the HLD flows.
# Needs: make up (all services running). Uses only curl, grep and sed.
set -euo pipefail

MAPPING=${MAPPING:-http://localhost:8082}
BFF=${BFF:-http://localhost:8080}
EHI=${EHI:-http://localhost:8083}
SIM=${SIM:-http://localhost:8090}
WEB=${WEB:-http://localhost:3000}

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

echo "4. Onboarding: Asha signs up (BFF → Temporal → mapping → Atfinity → Essence → Thredd)"
onb=$(curl -sf -X POST "$BFF/v1/onboarding" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuid)" \
  -d '{"customerType":"INDIVIDUAL","name":"Asha Patel","email":"asha@example.com"}')
ONB=$(echo "$onb" | field onboardingId)
[ -n "$ONB" ] && pass "202 Accepted: onboarding $ONB" || fail "No onboardingId in $onb"
for _ in $(seq 1 45); do
  st=$(curl -s "$BFF/v1/onboarding/$ONB")
  state=$(echo "$st" | field status)
  [ "$state" = "COMPLETED" ] || [ "$state" = "FAILED" ] || [ "$state" = "REJECTED" ] && break
  sleep 2
done
[ "$state" = "COMPLETED" ] && pass "Onboarding COMPLETED: $st" || fail "Onboarding ended as '$state': $st"
ASHA=$(echo "$st" | field customerId)
ASHA_ACC=$(echo "$st" | field accountId)
ASHA_TOKEN=$(curl -sf "$MAPPING/v1/customers/$ASHA/graph" | grep -o '"entityType":"CARD","externalId":"[^"]*"' | sed 's/.*"externalId":"\([^"]*\)"/\1/')
[ -n "$ASHA_TOKEN" ] && pass "Mapping holds Asha's KYC case, party, account $ASHA_ACC and card $ASHA_TOKEN" \
  || fail "No card linked for customer $ASHA"

echo "5. Onboarding: a KYC rejection ends with the customer REJECTED"
onb=$(curl -sf -X POST "$BFF/v1/onboarding" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuid)" \
  -d '{"customerType":"INDIVIDUAL","name":"Reject Test","email":"reject@example.com"}')
ONB2=$(echo "$onb" | field onboardingId)
for _ in $(seq 1 30); do
  st=$(curl -s "$BFF/v1/onboarding/$ONB2"); state=$(echo "$st" | field status)
  [ "$state" = "REJECTED" ] || [ "$state" = "COMPLETED" ] || [ "$state" = "FAILED" ] && break
  sleep 2
done
[ "$state" = "REJECTED" ] && pass "Onboarding REJECTED as expected" || fail "Expected REJECTED, got '$state': $st"

echo "6. Asha receives £100 by Faster Payment, then taps her new card (Thredd → EHI responder)"
curl -sf -X POST "$SIM/essence/v1/sim/inbound" -H 'Content-Type: application/json' \
  -d "{\"creditAccountId\":\"$ASHA_ACC\",\"amount\":\"100.00\",\"debtorName\":\"Arjun Mehta\",\"reference\":\"Welcome\"}" >/dev/null
bal=$(curl -sf "$SIM/essence/v1/accounts/$ASHA_ACC/balance" | field available)
[ "$bal" = "100.00" ] && pass "Asha's balance is £$bal" || fail "Expected 100.00, got $bal"
tap=$(curl -sf -X POST "$SIM/thredd/v1/sim/card-tap" -H 'Content-Type: application/json' \
  -d "{\"token\":\"$ASHA_TOKEN\",\"amount\":\"3.50\",\"merchant\":\"Bean There Cafe\"}")
echo "$tap" | grep -q '"decision":"APPROVE"' && pass "Card tap approved: $tap" || fail "Expected APPROVE, got $tap"

echo "7. Card settlement: the cafe settles the £3.50 tap (presentment captures the Essence hold)"
TAP_MSG=$(echo "$tap" | field messageId)
before=$(curl -sf "$SIM/essence/v1/accounts/$ASHA_ACC/balance")
echo "$before" | grep -q '"available":"96.50"' && echo "$before" | grep -q '"ledger":"100.00"' \
  && pass "Hold in place: available £96.50, ledger £100.00" || fail "Expected a £3.50 hold, got $before"
ack=$(curl -sf -X POST "$SIM/thredd/v1/sim/settle" -H 'Content-Type: application/json' \
  -d "{\"token\":\"$ASHA_TOKEN\",\"messageId\":\"$TAP_MSG\",\"amount\":\"3.50\"}")
after=$(curl -sf "$SIM/essence/v1/accounts/$ASHA_ACC/balance")
echo "$after" | grep -q '"ledger":"96.50"' && pass "Settled: $after" || fail "Expected ledger 96.50 after settlement, got $after ($ack)"

echo "8. Declines with reasons: not enough money, and the daily card limit"
tap=$(curl -sf -X POST "$SIM/thredd/v1/sim/card-tap" -H 'Content-Type: application/json' \
  -d "{\"token\":\"$ASHA_TOKEN\",\"amount\":\"200.00\",\"merchant\":\"Volt Electronics\"}")
echo "$tap" | grep -q '"reason":"INSUFFICIENT_FUNDS"' && pass "£200 declined: INSUFFICIENT_FUNDS" || fail "Expected INSUFFICIENT_FUNDS, got $tap"
tap=$(curl -sf -X POST "$SIM/thredd/v1/sim/card-tap" -H 'Content-Type: application/json' \
  -d "{\"token\":\"$TOKEN\",\"amount\":\"350.00\",\"merchant\":\"Volt Electronics\"}")
echo "$tap" | grep -q '"reason":"LIMIT_EXCEEDED"' && pass "Priya's £350 declined: LIMIT_EXCEEDED (daily limit £300)" \
  || fail "Expected LIMIT_EXCEEDED, got $tap"

echo "9. Sanctions hit: Atfinity rescreening freezes Asha and her next tap is declined"
CASE=$(curl -sf "$MAPPING/v1/customers/$ASHA/graph" | grep -o '"entityType":"KYC_CASE","externalId":"[^"]*"' | sed 's/.*"externalId":"\([^"]*\)"/\1/')
curl -sf -X POST "$SIM/atfinity/v1/sim/cases/$CASE/rescreen-hit" >/dev/null
for _ in $(seq 1 15); do
  tap=$(curl -sf -X POST "$SIM/thredd/v1/sim/card-tap" -H 'Content-Type: application/json' \
    -d "{\"token\":\"$ASHA_TOKEN\",\"amount\":\"1.00\",\"merchant\":\"Corner Shop\"}")
  echo "$tap" | grep -q '"reason":"CUSTOMER_FROZEN"' && break
  sleep 2
done
echo "$tap" | grep -q '"reason":"CUSTOMER_FROZEN"' && pass "Declined: CUSTOMER_FROZEN" || fail "Expected CUSTOMER_FROZEN, got $tap"
status=$(curl -sf "$MAPPING/v1/customers/$ASHA" | field status)
[ "$status" = "FROZEN" ] && pass "Asha's customer status is FROZEN" || fail "Expected FROZEN, got $status"

echo "10. Other-bank payment: Priya pays £15 to Barclays (Finastra routes it and reports scheme status)"
resp=$(curl -sf -X POST "$BFF/v1/payments" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuid)" \
  -H "X-Customer-Id: $CUSTOMER" \
  -d '{"debtorAccount":"ACC-5501","creditor":{"name":"Arjun Mehta","sortCode":"040004","accountNumber":"12345678"},"amount":{"value":"15.00","currency":"GBP"},"remittanceInfo":"Dinner","copResult":"MATCH"}')
OUT=$(echo "$resp" | field paymentId)
for _ in $(seq 1 20); do
  p=$(curl -s "$BFF/v1/payments/$OUT"); state=$(echo "$p" | grep -o '"status":"[A-Z_]*"' | head -1 | sed 's/.*:"\(.*\)"/\1/')
  [ "$state" = "COMPLETED" ] || [ "$state" = "REJECTED" ] || [ "$state" = "FAILED" ] && break
  sleep 1
done
[ "$state" = "COMPLETED" ] && echo "$p" | grep -q '"to":"ACCEPTED"' && pass "FPS payment COMPLETED via SUBMITTED → ACCEPTED (Finastra $(echo "$p" | field finastraRef))" \
  || fail "Expected COMPLETED with an ACCEPTED step, got: $p"

echo "11. Scheme rejection: a payment to sort code 99-00-00 is rejected and the money returned"
resp=$(curl -sf -X POST "$BFF/v1/payments" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuid)" \
  -H "X-Customer-Id: $CUSTOMER" \
  -d '{"debtorAccount":"ACC-5501","creditor":{"name":"Nobody","sortCode":"990000","accountNumber":"00000000"},"amount":{"value":"10.00","currency":"GBP"},"remittanceInfo":"Test"}')
REJ=$(echo "$resp" | field paymentId)
for _ in $(seq 1 20); do
  p=$(curl -s "$BFF/v1/payments/$REJ"); state=$(echo "$p" | grep -o '"status":"[A-Z_]*"' | head -1 | sed 's/.*:"\(.*\)"/\1/')
  [ "$state" = "REJECTED" ] || [ "$state" = "COMPLETED" ] || [ "$state" = "FAILED" ] && break
  sleep 1
done
[ "$state" = "REJECTED" ] && echo "$p" | grep -q 'PAY-SCHEME-001' && pass "REJECTED with PAY-SCHEME-001" || fail "Expected scheme rejection, got: $p"

echo "12. Inbound payments are recorded: COMPLETED for an active customer, FLAGGED once frozen"
list=$(curl -sf "$BFF/v1/payments" -H "X-Customer-Id: $ASHA")
echo "$list" | grep -q '"paymentType":"INBOUND","status":"COMPLETED"' && pass "Asha's £100 welcome payment recorded as INBOUND COMPLETED" \
  || fail "No completed inbound payment for Asha: $list"
curl -sf -X POST "$SIM/essence/v1/sim/inbound" -H 'Content-Type: application/json' \
  -d "{\"creditAccountId\":\"$ASHA_ACC\",\"amount\":\"5.00\",\"debtorName\":\"Arjun Mehta\",\"reference\":\"Coffee\"}" >/dev/null
for _ in $(seq 1 15); do
  list=$(curl -sf "$BFF/v1/payments" -H "X-Customer-Id: $ASHA")
  echo "$list" | grep -q '"status":"FLAGGED"' && break
  sleep 1
done
echo "$list" | grep -q '"status":"FLAGGED"' && pass "£5 to frozen Asha recorded as FLAGGED for review" || fail "Expected a FLAGGED inbound payment: $list"

echo "13. Web app: served by nginx, talking to the BFF"
curl -sf "$WEB/" | grep -q "Banking Platform Prototype" && pass "Web app is up at $WEB" || fail "Web app not served at $WEB"
me=$(curl -sf "$WEB/v1/me" -H "X-Customer-Id: $CUSTOMER")
echo "$me" | grep -q '"accountId":"ACC-5501"' && pass "Home screen data through the web app: $(echo "$me" | head -c 160)…" \
  || fail "Unexpected /v1/me through the web app: $me"

echo
echo "All checks passed. Search the logs for $CORR to follow the payment across services."
