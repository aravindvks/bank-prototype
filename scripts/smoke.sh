#!/usr/bin/env bash
# End-to-end smoke test, using Priya, Ravi and Asha from the HLD flows.
# Needs: make up (all services running). Uses only curl, grep and sed.
set -euo pipefail

MAPPING=${MAPPING:-http://localhost:8082}
BFF=${BFF:-http://localhost:8080}
EHI=${EHI:-http://localhost:8083}
SIM=${SIM:-http://localhost:8090}
WEB=${WEB:-http://localhost:3000}
CONNECT=${CONNECT:-http://localhost:8088}
KC=${KC:-http://localhost:8180}
TEMPO=${TEMPO:-http://localhost:3200}
PROM=${PROM:-http://localhost:9090}
LOKI=${LOKI:-http://localhost:3100}

# Right after make up the Java services are still starting: wait until each answers its health check.
printf 'Waiting for services'
for port in 8090 8082 8084 8085 8086 8083 8080 8081 8087; do
  for i in $(seq 1 120); do
    curl -sf "http://localhost:$port/actuator/health" >/dev/null 2>&1 && break
    [ "$i" = 120 ] && { echo; echo "Service on port $port did not become healthy within 4 minutes (docker compose ps)"; exit 1; }
    printf '.'; sleep 2
  done
done
# Keycloak (login) also starts slowly; wait for its realm if it is running at all.
if curl -s -o /dev/null "$KC/" 2>/dev/null || docker ps --format '{{.Names}}' 2>/dev/null | grep -q keycloak; then
  for i in $(seq 1 90); do curl -sf "$KC/realms/pbp" >/dev/null 2>&1 && break; printf '.'; sleep 2; done
fi
echo " ready"

# Login: with Keycloak up, every call carries a bearer token (as the web app does);
# otherwise the BFF runs with development headers.
token() { curl -sf -X POST "$KC/realms/pbp/protocol/openid-connect/token" -d grant_type=password \
  -d client_id=pbp-web -d "username=$1" -d "password=$2" | grep -o '"access_token":"[^"]*"' | sed 's/"access_token":"\(.*\)"/\1/'; }
if curl -sf "$KC/realms/pbp" >/dev/null 2>&1; then
  AUTH=1
  STAFF_TOKEN=$(token aravind staff123); PRIYA_TOKEN=$(token priya priya123); ASHA_TOKEN=$(token asha asha123)
  OPS_TOKEN=$(token ops ops123)
  STAFF_H="Authorization: Bearer $STAFF_TOKEN"; ASHA_H0="Authorization: Bearer $ASHA_TOKEN"
  [ -n "$STAFF_TOKEN" ] && [ -n "$PRIYA_TOKEN" ] && [ -n "$ASHA_TOKEN" ] || { echo "Could not sign in to Keycloak"; exit 1; }
else
  AUTH=0; STAFF_H="X-Staff-User: smoke-reviewer"; ASHA_H0="X-Smoke: 1"
fi


pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; exit 1; }
# Value of a JSON string field; empty (not a failure) when the field is not there yet, e.g. while polling.
field() { { grep -o "\"$1\":\"[^\"]*\"" || true; } | head -1 | sed "s/\"$1\":\"\(.*\)\"/\1/"; }
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen | tr 'A-Z' 'a-z'; }
# Top-level status of a payment (GET /v1/payments/{id}); history entries use from/to, not status.
pstatus() { curl -s "$BFF/v1/payments/$1" -H "$PRIYA_H" | { grep -o '"status":"[A-Z_]*"' || true; } | head -1 | sed 's/.*:"\(.*\)"/\1/'; }
# Waits until a payment reaches one of the statuses in $2 (a grep -E pattern); prints the payment.
wait_payment() { local p st; for _ in $(seq 1 "${3:-20}"); do st=$(pstatus "$1"); echo "$st" | grep -qE "^($2)$" && break; sleep 1; done
  curl -s "$BFF/v1/payments/$1" -H "$PRIYA_H"; }
# Payment ID of the row in a payments list with the given Finastra or Bacs reference.
row_id() { echo "$1" | { grep -oE "\"paymentId\":\"[^\"]*\",\"correlationId\":\"[^\"]*\",\"customerId\":\"[^\"]*\",\"debitAccount\":\"[^\"]*\",\"creditor\":\"(\\\\.|[^\"\\\\])*\",\"amount\":\"[^\"]*\",\"currency\":\"[^\"]*\",\"paymentType\":\"[^\"]*\",\"status\":\"[^\"]*\",\"finastraRef\":\"$2\"" || true; } | head -1 | field paymentId; }
# The fields of one schedule or mandate in a list, from its ID to the end of that entry.
entry() { echo "$1" | { grep -o "\"$2\":\"$3\".*" || true; } | sed "s/\"$4\".*//"; }
pay() { curl -sf -X POST "$BFF/v1/payments" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuid)" -H "$PRIYA_H" -d "$1" | field paymentId; }

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
if [ "$AUTH" = 1 ]; then
  curl -sf -X POST "$BFF/v1/staff/logins" -H 'Content-Type: application/json' -H "$STAFF_H" \
    -d "{\"username\":\"priya\",\"customerId\":\"$CUSTOMER\"}" >/dev/null
  PRIYA_H="Authorization: Bearer $PRIYA_TOKEN"
  pass "Staff linked online banking login 'priya' to customer $CUSTOMER"
else
  PRIYA_H="X-Customer-Id: $CUSTOMER"
fi

# The simulator keeps balances between runs, and each run spends some of Priya's money: top her up to £2,000.
avail=$(curl -sf "$SIM/essence/v1/accounts/ACC-5501/balance" | field available)
topup=$(awk "BEGIN { t = 2000 - ${avail:-0}; if (t > 0) printf \"%.2f\", t }")
if [ -n "$topup" ]; then
  curl -sf -X POST "$SIM/essence/v1/sim/inbound" -H 'Content-Type: application/json' \
    -d "{\"creditAccountId\":\"ACC-5501\",\"amount\":\"$topup\",\"debtorName\":\"Priya Shah\",\"reference\":\"Test top-up\"}" >/dev/null
  pass "Topped up Priya's available balance from £$avail to £2,000.00 (balances persist between runs)"
fi

echo "2. Card tap: £3.50 at a cafe (EHI responder)"
auth=$(curl -sf -X POST "$EHI/ehi/v1/messages" -H 'Content-Type: application/json' \
  -d "{\"messageId\":\"EHI-$(uuid)\",\"messageType\":\"AUTHORISATION\",\"cardToken\":\"$TOKEN\",\"amount\":\"3.50\",\"currency\":\"GBP\",\"merchant\":{\"name\":\"Bean There Cafe\",\"mcc\":\"5814\",\"channel\":\"contactless\"}}")
echo "$auth" | grep -q '"decision":"APPROVE"' && pass "Approved: $auth" || fail "Expected APPROVE, got $auth"

echo "3. Same-bank payment: Priya pays Ravi £25.00 (BFF → Kafka → Temporal → Essence adapter)"
before=$(curl -sf "$SIM/essence/v1/accounts/ACC-5510/balance" | field available)
KEY=$(uuid)
BODY='{"debtorAccount":"ACC-5501","creditor":{"name":"Ravi Kumar","sortCode":"200001","accountNumber":"41550198"},"amount":{"value":"25.00","currency":"GBP"},"purpose":"OTHER","remittanceInfo":"Smoke test","copResult":"MATCH"}'
resp=$(curl -sf -X POST "$BFF/v1/payments" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -H "$PRIYA_H" -d "$BODY")
PAYMENT=$(echo "$resp" | field paymentId)
CORR=$(echo "$resp" | field correlationId)
[ -n "$PAYMENT" ] && pass "202 Accepted: payment $PAYMENT, correlation $CORR" || fail "No paymentId in $resp"

replay=$(curl -sf -X POST "$BFF/v1/payments" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -H "$PRIYA_H" -d "$BODY")
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
onb=$(curl -sf -X POST "$BFF/v1/onboarding" -H 'Content-Type: application/json' -H "$ASHA_H0" -H "Idempotency-Key: $(uuid)" \
  -d '{"customerType":"INDIVIDUAL","name":"Asha Patel","email":"asha@example.com"}')
ONB=$(echo "$onb" | field onboardingId)
[ -n "$ONB" ] && pass "202 Accepted: onboarding $ONB" || fail "No onboardingId in $onb"
for _ in $(seq 1 45); do
  st=$(curl -s "$BFF/v1/onboarding/$ONB" -H "$ASHA_H0")
  state=$(echo "$st" | field status)
  [ "$state" = "COMPLETED" ] || [ "$state" = "FAILED" ] || [ "$state" = "REJECTED" ] && break
  sleep 2
done
[ "$state" = "COMPLETED" ] && pass "Onboarding COMPLETED: $st" || fail "Onboarding ended as '$state': $st"
ASHA=$(echo "$st" | field customerId)
if [ "$AUTH" = 1 ]; then ASHA_H="Authorization: Bearer $ASHA_TOKEN"; else ASHA_H="X-Customer-Id: $ASHA"; fi
ASHA_ACC=$(echo "$st" | field accountId)
cust=$(curl -sf "$MAPPING/v1/customers/$ASHA")
echo "$cust" | grep -q '"status":"ACTIVE"' && echo "$cust" | grep -q '"kycStatus":"VALID"' \
  && pass "Customer ACTIVE with KYC VALID (orchestrator PATCH calls to the mapping service work)" \
  || fail "Expected ACTIVE and KYC VALID, got $cust"
ASHA_CARD=$(curl -sf "$MAPPING/v1/customers/$ASHA/graph" | grep -o '"entityType":"CARD","externalId":"[^"]*"' | sed 's/.*"externalId":"\([^"]*\)"/\1/')
[ -n "$ASHA_CARD" ] && pass "Mapping holds Asha's KYC case, party, account $ASHA_ACC and card $ASHA_CARD" \
  || fail "No card linked for customer $ASHA"

echo "5. Onboarding: a KYC rejection ends with the customer REJECTED"
onb=$(curl -sf -X POST "$BFF/v1/onboarding" -H 'Content-Type: application/json' -H "$ASHA_H0" -H "Idempotency-Key: $(uuid)" \
  -d '{"customerType":"INDIVIDUAL","name":"Reject Test","email":"reject@example.com"}')
ONB2=$(echo "$onb" | field onboardingId)
for _ in $(seq 1 30); do
  st=$(curl -s "$BFF/v1/onboarding/$ONB2" -H "$ASHA_H0"); state=$(echo "$st" | field status)
  [ "$state" = "REJECTED" ] || [ "$state" = "COMPLETED" ] || [ "$state" = "FAILED" ] && break
  sleep 2
done
[ "$state" = "REJECTED" ] && pass "Onboarding REJECTED as expected" || fail "Expected REJECTED, got '$state': $st"

echo "6. Asha receives £100 by Faster Payment, then taps her new card (Thredd → EHI responder)"
curl -sf -X POST "$SIM/essence/v1/sim/inbound" -H 'Content-Type: application/json' \
  -d "{\"creditAccountId\":\"$ASHA_ACC\",\"amount\":\"100.00\",\"debtorName\":\"Arjun Mehta\",\"reference\":\"Welcome\"}" >/dev/null
bal=$(curl -sf "$SIM/essence/v1/accounts/$ASHA_ACC/balance" | field available)
[ "$bal" = "100.00" ] && pass "Asha's balance is £$bal" || fail "Expected 100.00, got $bal"
# Let the bank record the £100 (inbound saga) before step 9 freezes Asha, or it is correctly FLAGGED instead.
for _ in $(seq 1 30); do curl -sf "$BFF/v1/payments" -H "$ASHA_H" | grep -q '"paymentType":"INBOUND","status":"COMPLETED"' && break; sleep 1; done
tap=$(curl -sf -X POST "$SIM/thredd/v1/sim/card-tap" -H 'Content-Type: application/json' \
  -d "{\"token\":\"$ASHA_CARD\",\"amount\":\"3.50\",\"merchant\":\"Bean There Cafe\"}")
echo "$tap" | grep -q '"decision":"APPROVE"' && pass "Card tap approved: $tap" || fail "Expected APPROVE, got $tap"

echo "7. Card settlement: the cafe settles the £3.50 tap (presentment captures the Essence hold)"
TAP_MSG=$(echo "$tap" | field messageId)
before=$(curl -sf "$SIM/essence/v1/accounts/$ASHA_ACC/balance")
echo "$before" | grep -q '"available":"96.50"' && echo "$before" | grep -q '"ledger":"100.00"' \
  && pass "Hold in place: available £96.50, ledger £100.00" || fail "Expected a £3.50 hold, got $before"
ack=$(curl -sf -X POST "$SIM/thredd/v1/sim/settle" -H 'Content-Type: application/json' \
  -d "{\"token\":\"$ASHA_CARD\",\"messageId\":\"$TAP_MSG\",\"amount\":\"3.50\"}")
after=$(curl -sf "$SIM/essence/v1/accounts/$ASHA_ACC/balance")
echo "$after" | grep -q '"ledger":"96.50"' && pass "Settled: $after" || fail "Expected ledger 96.50 after settlement, got $after ($ack)"

echo "8. Declines with reasons: not enough money, and the daily card limit"
tap=$(curl -sf -X POST "$SIM/thredd/v1/sim/card-tap" -H 'Content-Type: application/json' \
  -d "{\"token\":\"$ASHA_CARD\",\"amount\":\"200.00\",\"merchant\":\"Volt Electronics\"}")
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
    -d "{\"token\":\"$ASHA_CARD\",\"amount\":\"1.00\",\"merchant\":\"Corner Shop\"}")
  echo "$tap" | grep -q '"reason":"CUSTOMER_FROZEN"' && break
  sleep 2
done
echo "$tap" | grep -q '"reason":"CUSTOMER_FROZEN"' && pass "Declined: CUSTOMER_FROZEN" || fail "Expected CUSTOMER_FROZEN, got $tap"
status=$(curl -sf "$MAPPING/v1/customers/$ASHA" | field status)
[ "$status" = "FROZEN" ] && pass "Asha's customer status is FROZEN" || fail "Expected FROZEN, got $status"

echo "10. Other-bank payment: Priya pays £15 to Barclays (Finastra routes it and reports scheme status)"
resp=$(curl -sf -X POST "$BFF/v1/payments" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuid)" \
  -H "$PRIYA_H" \
  -d '{"debtorAccount":"ACC-5501","creditor":{"name":"Arjun Mehta","sortCode":"040004","accountNumber":"12345678"},"amount":{"value":"15.00","currency":"GBP"},"remittanceInfo":"Dinner","copResult":"MATCH"}')
OUT=$(echo "$resp" | field paymentId)
for _ in $(seq 1 20); do
  p=$(curl -s "$BFF/v1/payments/$OUT" -H "$PRIYA_H"); state=$(pstatus "$OUT")
  [ "$state" = "COMPLETED" ] || [ "$state" = "REJECTED" ] || [ "$state" = "FAILED" ] && break
  sleep 1
done
[ "$state" = "COMPLETED" ] && echo "$p" | grep -q '"to":"ACCEPTED"' && pass "FPS payment COMPLETED via SUBMITTED → ACCEPTED (Finastra $(echo "$p" | field finastraRef))" \
  || fail "Expected COMPLETED with an ACCEPTED step, got: $p"

echo "11. Scheme rejection: a payment to sort code 99-00-00 is rejected and the money returned"
resp=$(curl -sf -X POST "$BFF/v1/payments" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuid)" \
  -H "$PRIYA_H" \
  -d '{"debtorAccount":"ACC-5501","creditor":{"name":"Nobody","sortCode":"990000","accountNumber":"31926819"},"amount":{"value":"10.00","currency":"GBP"},"remittanceInfo":"Test"}')
REJ=$(echo "$resp" | field paymentId)
for _ in $(seq 1 20); do
  p=$(curl -s "$BFF/v1/payments/$REJ" -H "$PRIYA_H"); state=$(pstatus "$REJ")
  [ "$state" = "REJECTED" ] || [ "$state" = "COMPLETED" ] || [ "$state" = "FAILED" ] && break
  sleep 1
done
[ "$state" = "REJECTED" ] && echo "$p" | grep -q 'PAY-SCHEME-001' && pass "REJECTED with PAY-SCHEME-001" || fail "Expected scheme rejection, got: $p"

echo "12. Inbound payments are recorded: COMPLETED for an active customer, FLAGGED once frozen"
list=$(curl -sf "$BFF/v1/payments" -H "$ASHA_H")
echo "$list" | grep -q '"paymentType":"INBOUND","status":"COMPLETED"' && pass "Asha's £100 welcome payment recorded as INBOUND COMPLETED" \
  || fail "No completed inbound payment for Asha: $list"
curl -sf -X POST "$SIM/essence/v1/sim/inbound" -H 'Content-Type: application/json' \
  -d "{\"creditAccountId\":\"$ASHA_ACC\",\"amount\":\"5.00\",\"debtorName\":\"Arjun Mehta\",\"reference\":\"Coffee\"}" >/dev/null
for _ in $(seq 1 15); do
  list=$(curl -sf "$BFF/v1/payments" -H "$ASHA_H")
  echo "$list" | grep -q '"status":"FLAGGED"' && break
  sleep 1
done
echo "$list" | grep -q '"status":"FLAGGED"' && pass "£5 to frozen Asha recorded as FLAGGED for review" || fail "Expected a FLAGGED inbound payment: $list"

echo "13. Web app: served by nginx, talking to the BFF"
curl -sf "$WEB/" | grep -q "Banking Platform Prototype" && pass "Web app is up at $WEB" || fail "Web app not served at $WEB"
me=$(curl -sf "$WEB/v1/me" -H "$PRIYA_H")
echo "$me" | grep -q '"accountId":"ACC-5501"' && pass "Home screen data through the web app: $(echo "$me" | head -c 160)…" \
  || fail "Unexpected /v1/me through the web app: $me"

echo "14. Staff review: clearing Asha's case unfreezes her, unblocks her card and releases the held £5"
q=$(curl -sf "$BFF/v1/staff/queue" -H "$STAFF_H")
SAGA=$(echo "$q" | grep -o "\"sagaId\":\"[^\"]*\",\"customerId\":\"$ASHA\"" | head -1 | sed 's/"sagaId":"\([^"]*\)".*/\1/')
[ -n "$SAGA" ] && pass "Asha's case is in the review queue (saga $SAGA)" || fail "No open review for Asha: $q"
curl -sf -X POST "$BFF/v1/staff/reviews/$SAGA" -H 'Content-Type: application/json' -H "$STAFF_H" \
  -d '{"decision":"CLEAR","note":"False positive (smoke test)"}' >/dev/null
for _ in $(seq 1 15); do
  status=$(curl -sf "$MAPPING/v1/customers/$ASHA" | field status)
  [ "$status" = "ACTIVE" ] && break
  sleep 1
done
[ "$status" = "ACTIVE" ] && pass "Asha is ACTIVE again" || fail "Expected ACTIVE after CLEAR, got $status"
for _ in $(seq 1 15); do
  list=$(curl -sf "$BFF/v1/payments" -H "$ASHA_H")
  echo "$list" | grep -q '"status":"FLAGGED"' || break
  sleep 1
done
echo "$list" | grep -q '"status":"FLAGGED"' && fail "Held payment was not released: $list" || pass "Held £5 released (no FLAGGED payments left)"
for _ in $(seq 1 10); do
  tap=$(curl -sf -X POST "$SIM/thredd/v1/sim/card-tap" -H 'Content-Type: application/json' \
    -d "{\"token\":\"$ASHA_CARD\",\"amount\":\"1.00\",\"merchant\":\"Corner Shop\"}")
  echo "$tap" | grep -q '"decision":"APPROVE"' && break
  sleep 2
done
echo "$tap" | grep -q '"decision":"APPROVE"' && pass "Asha's card works again" || fail "Expected APPROVE after CLEAR, got $tap"

echo "15. Card controls: Priya turns off online payments, raises her limit, then freezes her card"
ctl() { curl -sf -X PUT "$BFF/v1/cards/controls" -H 'Content-Type: application/json' -H "$PRIYA_H" -d "$1" >/dev/null; }
tapp() { curl -sf -X POST "$SIM/thredd/v1/sim/card-tap" -H 'Content-Type: application/json' \
  -d "{\"token\":\"$TOKEN\",\"amount\":\"$1\",\"merchant\":\"$2\",\"channel\":\"$3\"}"; }
ctl '{"frozen":false,"dailyLimit":"1000.00","online":false,"contactless":true,"atm":true}'
for _ in $(seq 1 15); do t=$(tapp 20.00 "Northwind Online" online); echo "$t" | grep -q CHANNEL_BLOCKED && break; sleep 1; done
echo "$t" | grep -q '"reason":"CHANNEL_BLOCKED"' && pass "Online purchase declined: CHANNEL_BLOCKED" || fail "Expected CHANNEL_BLOCKED, got $t"
t=$(tapp 350.00 "Volt Electronics" chip)
echo "$t" | grep -q '"decision":"APPROVE"' && pass "£350 approved under the new £1,000 limit" || fail "Expected APPROVE at £1,000 limit, got $t"
ctl '{"frozen":true,"dailyLimit":"1000.00","online":false,"contactless":true,"atm":true}'
for _ in $(seq 1 15); do t=$(tapp 2.00 "Bean There Cafe" contactless); echo "$t" | grep -q CARD_BLOCKED && break; sleep 1; done
echo "$t" | grep -q '"reason":"CARD_BLOCKED"' && pass "Frozen card declined: CARD_BLOCKED" || fail "Expected CARD_BLOCKED, got $t"
ctl '{"frozen":false,"dailyLimit":"300.00","online":true,"contactless":true,"atm":true}'

echo "16. Live push: the app's event stream receives Priya's incoming payment as it happens"
EVENTS=$(mktemp)
curl -sN --max-time 20 "$WEB/v1/events?customerId=$CUSTOMER${PRIYA_TOKEN:+&access_token=$PRIYA_TOKEN}" > "$EVENTS" &
CURL_PID=$!
sleep 3
curl -sf -X POST "$SIM/essence/v1/sim/inbound" -H 'Content-Type: application/json' \
  -d '{"creditAccountId":"ACC-5501","amount":"1.00","debtorName":"Live Push Test","reference":"SSE"}' >/dev/null
for _ in $(seq 1 15); do grep -q "payment.received" "$EVENTS" && break; sleep 1; done
kill $CURL_PID 2>/dev/null || true
wait $CURL_PID 2>/dev/null || true
grep -q "payment.received" "$EVENTS" && pass "Pushed over Server-Sent Events: $(grep -o '"type":"payment.received"[^}]*' "$EVENTS" | head -1 | head -c 120)…" \
  || fail "No payment.received event on the stream: $(head -c 400 "$EVENTS")"

echo "17. Confirmation of Payee: the name is checked with the payee's bank before money moves"
cop() { curl -sf -X POST "$BFF/v1/payees/confirm" -H 'Content-Type: application/json' -H "$PRIYA_H" \
  -d "{\"name\":\"$1\",\"sortCode\":\"$2\",\"accountNumber\":\"$3\"}"; }
r=$(cop "Ravi Kumar" 200001 41550198); echo "$r" | grep -q '"result":"MATCH"' && pass "Ravi Kumar → MATCH" || fail "Expected MATCH, got $r"
r=$(cop "R Kumar" 200001 41550198)
echo "$r" | grep -q '"result":"CLOSE_MATCH"' && echo "$r" | grep -q '"matchedName":"Ravi Kumar"' \
  && pass "R Kumar → CLOSE_MATCH, account held by Ravi Kumar" || fail "Expected CLOSE_MATCH, got $r"
r=$(cop "John Smith" 200001 41550198); echo "$r" | grep -q '"result":"NO_MATCH"' && pass "John Smith → NO_MATCH" || fail "Expected NO_MATCH, got $r"
r=$(cop "Anyone" 980000 12345678); echo "$r" | grep -q '"result":"UNAVAILABLE"' && pass "Bank outside CoP → UNAVAILABLE" || fail "Expected UNAVAILABLE, got $r"
NM=$(pay '{"debtorAccount":"ACC-5501","creditor":{"name":"John Smith","sortCode":"200001","accountNumber":"41550198"},"amount":{"value":"5.00","currency":"GBP"},"remittanceInfo":"CoP test"}')
p=$(wait_payment "$NM" "REJECTED|COMPLETED|FAILED")
echo "$p" | grep -q '"status":"REJECTED"' && echo "$p" | grep -q 'PAY-COP-001' && pass "Payment to a name that does not match: REJECTED with PAY-COP-001" \
  || fail "Expected a PAY-COP-001 rejection, got $p"
CM=$(pay '{"debtorAccount":"ACC-5501","creditor":{"name":"R Kumar","sortCode":"200001","accountNumber":"41550198"},"amount":{"value":"5.00","currency":"GBP"},"remittanceInfo":"CoP test","copResult":"CLOSE_MATCH"}')
p=$(wait_payment "$CM" "REJECTED|COMPLETED|FAILED")
echo "$p" | grep -q '"status":"COMPLETED"' && echo "$p" | grep -q '"copResult":"CLOSE_MATCH"' \
  && pass "Close match the customer confirmed: COMPLETED, result kept on the payment" || fail "Expected COMPLETED after a confirmed close match, got $p"

echo "18. Standing orders and scheduled payments (Temporal timers; each run is a normal payment saga)"
TODAY=$(TZ=Europe/London date +%F)
TOMORROW=$(TZ=Europe/London date -d '+1 day' +%F 2>/dev/null || TZ=Europe/London date -v+1d +%F)
sched() { curl -sf -X POST "$BFF/v1/schedules" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuid)" -H "$PRIYA_H" -d "$1" | field scheduleId; }
SO=$(sched "{\"kind\":\"STANDING_ORDER\",\"frequency\":\"MONTHLY\",\"startDate\":\"$TODAY\",\"debtorAccount\":\"ACC-5501\",\"creditor\":{\"name\":\"Ravi Kumar\",\"sortCode\":\"200001\",\"accountNumber\":\"41550198\"},\"amount\":{\"value\":\"10.00\",\"currency\":\"GBP\"},\"reference\":\"Rent share\",\"copResult\":\"MATCH\"}")
[ -n "$SO" ] && pass "Monthly standing order $SO accepted, first payment today" || fail "No scheduleId for the standing order"
# Payments go out at 06:00 UK time. Before then today's payment is still waiting, so use the demo "pay now".
if [ "$(TZ=Europe/London date +%H)" -lt 6 ]; then
  for _ in $(seq 1 15); do curl -sf "$BFF/v1/schedules" -H "$PRIYA_H" | grep -q "\"scheduleId\":\"$SO\"" && break; sleep 1; done
  curl -sf -X POST "$BFF/v1/schedules/$SO/run-now" -H "$PRIYA_H" >/dev/null
  echo "  (before 06:00 UK: today's payment would wait until 06:00, so the demo 'pay now' sends it)"
fi
for _ in $(seq 1 25); do e=$(entry "$(curl -sf "$BFF/v1/schedules" -H "$PRIYA_H")" scheduleId "$SO" createdAt)
  echo "$e" | grep -q '"paymentsMade":1' && echo "$e" | grep -q '"lastStatus":"COMPLETED"' && break; sleep 1; done
echo "$e" | grep -q '"paymentsMade":1' && echo "$e" | grep -q '"lastStatus":"COMPLETED"' && ! echo "$e" | grep -q "\"nextDate\":\"$TODAY\"" \
  && pass "First payment made; next due $(echo "$e" | field nextDate)" || fail "Standing order did not pay today: $e"
curl -sf -X DELETE "$BFF/v1/schedules/$SO" -H "$PRIYA_H" >/dev/null
for _ in $(seq 1 15); do e=$(entry "$(curl -sf "$BFF/v1/schedules" -H "$PRIYA_H")" scheduleId "$SO" createdAt)
  echo "$e" | grep -q '"status":"CANCELLED"' && break; sleep 1; done
echo "$e" | grep -q '"status":"CANCELLED"' && pass "Standing order cancelled" || fail "Expected CANCELLED, got $e"
ONE=$(sched "{\"kind\":\"ONE_OFF\",\"startDate\":\"$TOMORROW\",\"debtorAccount\":\"ACC-5501\",\"creditor\":{\"name\":\"Ravi Kumar\",\"sortCode\":\"200001\",\"accountNumber\":\"41550198\"},\"amount\":{\"value\":\"7.00\",\"currency\":\"GBP\"},\"reference\":\"Tickets\",\"copResult\":\"MATCH\"}")
for _ in $(seq 1 15); do e=$(entry "$(curl -sf "$BFF/v1/schedules" -H "$PRIYA_H")" scheduleId "$ONE" createdAt)
  echo "$e" | grep -q "\"nextDate\":\"$TOMORROW\"" && break; sleep 1; done
echo "$e" | grep -q "\"nextDate\":\"$TOMORROW\"" && echo "$e" | grep -q '"paymentsMade":0' && pass "Payment scheduled for $TOMORROW, nothing paid yet" \
  || fail "Expected a payment due $TOMORROW, got $e"
curl -sf -X POST "$BFF/v1/schedules/$ONE/run-now" -H "$PRIYA_H" >/dev/null
for _ in $(seq 1 25); do e=$(entry "$(curl -sf "$BFF/v1/schedules" -H "$PRIYA_H")" scheduleId "$ONE" createdAt)
  echo "$e" | grep -q '"status":"COMPLETED"' && break; sleep 1; done
echo "$e" | grep -q '"status":"COMPLETED"' && echo "$e" | grep -q '"lastStatus":"COMPLETED"' && pass "Demo 'pay now': the scheduled payment was made and the schedule ended" \
  || fail "Expected the scheduled payment to complete, got $e"

echo "19. Bacs Direct Credit: Priya's salary arrives in a Bacs file from her employer"
b=$(curl -sf -X POST "$SIM/essence/v1/sim/bacs/credits" -H 'Content-Type: application/json' \
  -d '{"originatorName":"ACME LTD","credits":[{"creditAccountId":"ACC-5501","amount":"2500.00","reference":"SALARY NOV"}]}')
BREF=$(echo "$b" | field finastraRef)
for _ in $(seq 1 20); do list=$(curl -sf "$BFF/v1/payments" -H "$PRIYA_H")
  echo "$list" | grep -q "\"status\":\"COMPLETED\",\"finastraRef\":\"$BREF\"" && break; sleep 1; done
echo "$list" | grep -q "\"paymentType\":\"INBOUND\",\"status\":\"COMPLETED\",\"finastraRef\":\"$BREF\"" \
  && pass "£2,500 from ACME LTD recorded as an INBOUND Bacs credit ($BREF)" || fail "Bacs credit $BREF not completed: $(echo "$list" | head -c 400)"

echo "20. Direct Debits: a mandate, a collection paid, one returned unpaid, a Guarantee refund, then cancellation"
m=$(curl -sf -X POST "$SIM/essence/v1/sim/dd/mandates" -H 'Content-Type: application/json' \
  -d '{"accountId":"ACC-5501","originatorName":"Brightside Energy","serviceUserNumber":"275431","payerReference":"BE-PRIYA-01"}')
MREF=$(echo "$m" | field mandateRef)
for _ in $(seq 1 15); do dd=$(curl -sf "$BFF/v1/direct-debits" -H "$PRIYA_H"); e=$(entry "$dd" mandateRef "$MREF" createdAt)
  echo "$e" | grep -q '"status":"ACTIVE"' && break; sleep 1; done
MID=$(echo "$dd" | grep -o "\"mandateId\":\"[^\"]*\",\"mandateRef\":\"$MREF\"" | field mandateId)
[ -n "$MID" ] && pass "AUDDIS: Brightside Energy's mandate $MREF is ACTIVE on Priya's account" || fail "Mandate not set up: $dd"
collect() { curl -sf -X POST "$SIM/essence/v1/sim/dd/collections" -H 'Content-Type: application/json' \
  -d "{\"mandateRef\":\"$MREF\",\"amount\":\"$1\"}" | field collectionRef; }
dd_row() { for _ in $(seq 1 20); do list=$(curl -sf "$BFF/v1/payments" -H "$PRIYA_H")
  echo "$list" | grep -q "\"paymentType\":\"DIRECT_DEBIT\",\"status\":\"$2\",\"finastraRef\":\"$1\"" && { row_id "$list" "$1"; return; }
  sleep 1; done; }  # empty when the collection never reached that status
C1=$(collect 30.00); DD1=$(dd_row "$C1" COMPLETED)
[ -n "$DD1" ] && pass "£30 collection $C1 paid on its due date (payment $DD1)" || fail "Collection $C1 not paid: $(curl -sf "$BFF/v1/payments" -H "$PRIYA_H" | head -c 400)"
# More than Priya has (balances build up over runs, so it is worked out from her balance now).
BIG=$(awk "BEGIN { printf \"%.2f\", $(curl -sf "$SIM/essence/v1/accounts/ACC-5501/balance" | field available) + 1000 }")
C2=$(collect "$BIG"); DD2=$(dd_row "$C2" RETURNED)
p=$(curl -s "$BFF/v1/payments/$DD2" -H "$PRIYA_H")
echo "$p" | grep -q 'ARUDD 0' && pass "£$BIG collection (more than her balance) returned unpaid: ARUDD 0 (refer to payer)" || fail "Expected an ARUDD 0 return, got $p"
curl -sf -X POST "$BFF/v1/direct-debits/claims" -H 'Content-Type: application/json' -H "$PRIYA_H" -d "{\"paymentId\":\"$DD1\"}" >/dev/null
p=$(wait_payment "$DD1" "REFUNDED")
echo "$p" | grep -q '"status":"REFUNDED"' && pass "Direct Debit Guarantee: £30 refunded at once, indemnity claim raised on the originator" \
  || fail "Expected REFUNDED, got $p"
curl -sf -X DELETE "$BFF/v1/direct-debits/$MID" -H "$PRIYA_H" >/dev/null
for _ in $(seq 1 15); do e=$(entry "$(curl -sf "$BFF/v1/direct-debits" -H "$PRIYA_H")" mandateRef "$MREF" createdAt)
  echo "$e" | grep -q '"status":"CANCELLED"' && break; sleep 1; done
echo "$e" | grep -q '"status":"CANCELLED"' && pass "Mandate cancelled by Priya (ADDACS sent to the originator)" || fail "Expected CANCELLED, got $e"
C3=$(collect 30.00); DD3=$(dd_row "$C3" RETURNED)
p=$(curl -s "$BFF/v1/payments/$DD3" -H "$PRIYA_H")
echo "$p" | grep -q 'ARUDD 1' && pass "A later collection is returned: ARUDD 1 (instruction cancelled)" || fail "Expected an ARUDD 1 return, got $p"

echo "21. CHAPS: a £12,000 house deposit is held for staff approval, then sent within CHAPS hours with a £20 fee"
curl -sf -X POST "$SIM/essence/v1/sim/inbound" -H 'Content-Type: application/json' \
  -d '{"creditAccountId":"ACC-5501","amount":"15000.00","debtorName":"Priya Shah","reference":"From savings"}' >/dev/null
sleep 2
CH=$(pay '{"debtorAccount":"ACC-5501","creditor":{"name":"Northwind Lettings Ltd","sortCode":"309634","accountNumber":"55667788"},"amount":{"value":"12000.00","currency":"GBP"},"remittanceInfo":"Deposit","copResult":"MATCH","scheme":"CHAPS"}')
p=$(wait_payment "$CH" "HELD|REJECTED|FAILED")
echo "$p" | grep -q '"status":"HELD"' && echo "$p" | grep -q '"paymentType":"CHAPS"' && pass "CHAPS payment $CH HELD for approval (£10,000 or more)" \
  || fail "Expected a HELD CHAPS payment, got $p"
q=$(curl -sf "$BFF/v1/staff/queue" -H "$STAFF_H")
echo "$q" | grep -q "\"paymentId\":\"$CH\"" && pass "It is in the staff Control Room queue" || fail "Held payment not in the staff queue: $q"
curl -sf -X POST "$BFF/v1/staff/payments/$CH/decision" -H 'Content-Type: application/json' -H "$STAFF_H" \
  -d '{"decision":"APPROVE","note":"Called the customer (smoke test)"}' >/dev/null
DOW=$(TZ=Europe/London date +%u); HM=$(TZ=Europe/London date +%H%M)
HOLIDAYS=" 2026-12-25 2026-12-28 2027-01-01 2027-03-26 2027-03-29 2027-05-03 2027-05-31 2027-08-30 2027-12-27 2027-12-28 "
if [ "$DOW" -le 5 ] && [ "$HM" -ge 0600 ] && [ "$HM" -lt 1730 ] && ! echo "$HOLIDAYS" | grep -q " $TODAY "; then
  p=$(wait_payment "$CH" "COMPLETED|REJECTED|FAILED|QUEUED")
  echo "$p" | grep -q '"status":"COMPLETED"' && echo "$p" | grep -q '"fee":"20.00"' && echo "$p" | grep -q 'Approved by' \
    && pass "Approved and settled by CHAPS; £20 fee charged" || fail "Expected COMPLETED with a £20 fee, got $p"
else
  p=$(wait_payment "$CH" "QUEUED|COMPLETED|REJECTED|FAILED")
  echo "$p" | grep -q '"status":"QUEUED"' && pass "Approved; CHAPS is closed now (UK $HM, day $DOW), so it waits for the next opening" \
    || fail "Expected QUEUED outside CHAPS hours, got $p"
fi

echo "22. Reconciliation: today's payments and card settlements all match Essence; a rogue posting is caught"
curl -sf -X POST "$SIM/essence/v1/sim/rogue-posting" -H 'Content-Type: application/json' -d '{"accountId":"ACC-5510","amount":"7.77"}' >/dev/null
rec=$(curl -s -w '\n%{http_code}' -X POST "$BFF/v1/staff/recon/runs" -H "$STAFF_H")
code=$(echo "$rec" | tail -1); rec=$(echo "$rec" | sed '$d')
[ "$code" = "200" ] || fail "Reconciliation run returned HTTP $code: $rec"
echo "$rec" | grep -q '"source":"PAYMENTS","matched":[1-9][0-9]*,"exceptions":0' && pass "PAYMENTS: all matched ($(echo "$rec" | grep -o '"source":"PAYMENTS"[^}]*'))" \
  || fail "Payments did not reconcile cleanly: $rec"
echo "$rec" | grep -q '"source":"CARDS","matched":[1-9][0-9]*,"exceptions":0' && pass "CARDS: all matched ($(echo "$rec" | grep -o '"source":"CARDS"[^}]*'))" \
  || fail "Card settlements did not reconcile cleanly: $rec"
exc=$(curl -sf "$BFF/v1/staff/recon/exceptions" -H "$STAFF_H")
echo "$exc" | grep -q '"type":"MISSING_IN_SOURCE","sourceRef":"ROGUE-' && pass "Rogue Essence posting reported as MISSING_IN_SOURCE" \
  || fail "Rogue posting not reported: $exc"

echo "23. Outbox relay: every event above travelled through service outbox tables and Debezium"
st=$(curl -sf "$CONNECT/connectors/pbp-outbox/status" || true)
if [ -n "$st" ]; then
  echo "$st" | grep -q '"state":"RUNNING"' && ! echo "$st" | grep -q '"state":"FAILED"' \
    && pass "Debezium outbox connector RUNNING (bff, orchestration, mapping, ehi outboxes)" || fail "Connector not healthy: $st"
else
  echo "  SKIP Kafka Connect not reachable at $CONNECT (services are in direct mode)"
fi

# ---- Observability (LLD section 15). Skipped when the Grafana stack is not running. ----
# Service names in one Tempo trace, space separated.
trace_services() { curl -s "$TEMPO/api/traces/$1" | { grep -o '"key":"service.name","value":{"stringValue":"[^"]*"' || true; } \
  | sed 's/.*"stringValue":"\(.*\)"/\1/' | sort -u | tr '\n' ' '; }
# Trace IDs matching a TraceQL query in the last hour.
traces() { curl -s -G "$TEMPO/api/search" --data-urlencode "q=$1" --data-urlencode "limit=20" \
  --data-urlencode "start=$(( $(date +%s) - 3600 ))" --data-urlencode "end=$(( $(date +%s) + 60 ))" \
  | { grep -o '"traceID":"[0-9a-f]*"' || true; } | sed 's/.*:"\(.*\)"/\1/'; }
# First value of an instant Prometheus query, or empty.
promq() { curl -s -G "$PROM/api/v1/query" --data-urlencode "query=$1" \
  | { grep -o '"value":\[[^]]*\]' || true; } | head -1 | sed 's/.*,"\(.*\)"\]/\1/'; }
has_all() { for s in $2; do echo " $1 " | grep -q " $s " || return 1; done; }

if curl -sf "$TEMPO/ready" >/dev/null 2>&1; then
  echo "24. Tracing: Priya's £25 payment is one trace from the BFF through Kafka and Temporal to the Essence adapter"
  want="bff orchestrator essence-adapter"; found=""; seen=""
  for _ in $(seq 1 30); do
    for t in $(traces "{ span.pbp.correlation_id = \"$CORR\" }"); do
      svc=$(trace_services "$t"); seen="$seen [$t: $svc]"
      has_all "$svc" "$want" && { found="$t ($svc)"; break 2; }
    done
    sleep 2
  done
  [ -n "$found" ] && pass "Trace $found" \
    || fail "No single trace for $CORR spans $want. Traces seen:${seen:- none}. Check traceparent in the outbox and the Temporal interceptors"

  echo "25. Tracing: a card tap is one trace from the EHI responder to the Essence hold"
  found=""
  for _ in $(seq 1 15); do
    t=$(traces '{ resource.service.name = "ehi-responder" } && { resource.service.name = "essence-adapter" }' | head -1)
    [ -n "$t" ] && { found="$t ($(trace_services "$t"))"; break; }
    sleep 2
  done
  [ -n "$found" ] && pass "Trace $found" || fail "No trace joins ehi-responder and essence-adapter"
else
  echo "  SKIP Tempo not reachable at $TEMPO (observability stack not running)"
fi

if curl -sf "$PROM/-/ready" >/dev/null 2>&1; then
  echo "26. Metrics, alerts and logs: every service scraped, flow metrics recorded, alert rules loaded, logs searchable"
  up=""; done_n=""; ehi_n=""
  for _ in $(seq 1 30); do
    up=$(promq 'count(up{job="pbp"} == 1)'); done_n=$(promq 'sum(pbp_payment_transitions_total{status="COMPLETED"})')
    ehi_n=$(promq 'sum(pbp_ehi_decision_seconds_count)')
    [ "$up" = 9 ] && [ -n "$done_n" ] && [ -n "$ehi_n" ] && break
    sleep 3
  done
  [ "$up" = 9 ] && pass "Prometheus scrapes all 9 services" || fail "Prometheus sees ${up:-0} of 9 services up (http://localhost:9090/targets)"
  [ -n "$done_n" ] && pass "Payment flow metrics: $done_n status changes to COMPLETED counted" \
    || fail "pbp_payment_transitions_total has no COMPLETED series (FlowMetricsCollector in the orchestrator)"
  [ -n "$ehi_n" ] && pass "Card authorisation timer: $ehi_n decisions recorded" || fail "pbp_ehi_decision_seconds has no samples"
  rules=$(curl -s "$PROM/api/v1/rules")
  echo "$rules" | grep -q '"name":"EhiOverTimeBudget"' && echo "$rules" | grep -q '"name":"StandingOrderMissed"' \
    && pass "Alert rules loaded ($(echo "$rules" | grep -o '"type":"alerting"' | wc -l | tr -d ' ') rules)" || fail "Alert rules not loaded: ${rules:0:300}"
  if curl -sf "$LOKI/ready" >/dev/null 2>&1; then
    lines=""
    for _ in $(seq 1 20); do
      lines=$(curl -s -G "$LOKI/loki/api/v1/query_range" --data-urlencode "query={service=\"orchestrator\"} |= \"cid=$CORR\"" \
        --data-urlencode "limit=5" --data-urlencode "start=$(( $(date +%s) - 3600 ))000000000" | { grep -o '"values":\[\[' || true; })
      [ -n "$lines" ] && break
      sleep 3
    done
    [ -n "$lines" ] && pass "Loki finds the orchestrator's log lines for $CORR" || fail "No orchestrator log lines in Loki for cid=$CORR"
  fi
else
  echo "  SKIP Prometheus not reachable at $PROM (observability stack not running)"
fi

if [ "$AUTH" = 1 ]; then
  echo "27. Login: no token is refused; a view-only staff login cannot decide reviews"
  code=$(curl -s -o /dev/null -w '%{http_code}' "$BFF/v1/me")
  [ "$code" = "401" ] && pass "No token → 401" || fail "Expected 401 without a token, got $code"
  code=$(curl -s -o /dev/null -w '%{http_code}' "$BFF/v1/staff/queue" -H "Authorization: Bearer $PRIYA_TOKEN")
  [ "$code" = "403" ] && pass "Customer token on the staff API → 403" || fail "Expected 403 for a customer on /v1/staff, got $code"
  code=$(curl -s -o /dev/null -w '%{http_code}' "$BFF/v1/staff/queue" -H "Authorization: Bearer $OPS_TOKEN")
  [ "$code" = "200" ] && pass "Staff role can view the Control Room" || fail "Expected 200 for ops on the queue, got $code"
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BFF/v1/staff/recon/runs" -H "Authorization: Bearer $OPS_TOKEN")
  [ "$code" = "403" ] && pass "Staff without compliance cannot run reconciliation → 403" || fail "Expected 403 for ops, got $code"
  me=$(curl -sf "$BFF/v1/me" -H "Authorization: Bearer $ASHA_TOKEN")
  echo "$me" | grep -q "\"customerId\":\"$ASHA\"" && pass "Login 'asha' was linked to her customer when sign-up completed" \
    || fail "Login asha not linked to $ASHA: $me"
fi

echo
echo "All checks passed. Follow the payment in Grafana (http://localhost:3001): paste $CORR into the Overview dashboard."
