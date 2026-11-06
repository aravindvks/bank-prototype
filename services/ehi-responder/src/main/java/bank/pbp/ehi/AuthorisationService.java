package bank.pbp.ehi;

import bank.pbp.common.correlation.Correlation;
import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.common.ids.Ids;
import bank.pbp.ehi.CardLookup.Card;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * EHI message handling (LLD section 5).
 * <p>Authorisation order: duplicate → card lookup (cache first) → card and customer status →
 * daily card limit → Essence hold (funds check) → respond, then log and publish off the response
 * path. Presentment captures the hold; reversal releases it.
 */
@Service
public class AuthorisationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorisationService.class);

    private final JdbcClient jdbc;
    private final EventPublisher events;
    private final ObjectMapper mapper;
    private final ExecutorService afterResponse;
    private final CardLookup cards;
    private final StringRedisTemplate redis;
    private final RestClient essence;

    public AuthorisationService(JdbcClient jdbc, EventPublisher events, ObjectMapper mapper,
                                ExecutorService afterResponseExecutor, CardLookup cards, StringRedisTemplate redis,
                                RestClient.Builder builder,
                                @Value("${pbp.ehi.essence-adapter-url}") String essenceUrl,
                                @Value("${pbp.ehi.essence-timeout-ms}") int essenceTimeoutMs) {
        this.jdbc = jdbc;
        this.events = events;
        this.mapper = mapper;
        this.afterResponse = afterResponseExecutor;
        this.cards = cards;
        this.redis = redis;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Math.min(essenceTimeoutMs, 500));
        f.setReadTimeout(essenceTimeoutMs);
        this.essence = builder.clone().baseUrl(essenceUrl).requestFactory(f).build();
    }

    public Decision handle(EhiMessage msg) {
        long start = System.nanoTime();
        Optional<Decision> duplicate = previousDecision(msg.messageId());
        if (duplicate.isPresent()) {
            return duplicate.get();
        }
        Card card = cards.find(msg.cardToken());
        Outcome outcome = switch (msg.messageType()) {
            case "PRESENTMENT" -> presentment(msg);
            case "REVERSAL" -> reversal(msg, card);
            default -> authorise(msg, card);
        };
        int latencyMs = (int) ((System.nanoTime() - start) / 1_000_000);
        Decision decision = new Decision(msg.messageId(), outcome.decision(), outcome.reason(), latencyMs);
        String correlationId = Correlation.current().orElse(null);
        afterResponse.submit(() -> record(msg, card, outcome, decision, correlationId));
        log.info("ehi.decision type={} messageId={} decision={} reason={} source={} latencyMs={}",
                msg.messageType(), msg.messageId(), decision.decision(), decision.reason(), card.source(), latencyMs);
        return decision;
    }

    private Outcome authorise(EhiMessage msg, Card card) {
        if (card.error() != null) {
            return Outcome.decline(card.error());
        }
        if (!"ACTIVE".equals(card.cardStatus())) {
            return Outcome.decline("CARD_BLOCKED");
        }
        if (!"ACTIVE".equals(card.customerStatus())) {
            return Outcome.decline("CUSTOMER_FROZEN");
        }
        long amountPence = pence(msg.amount());
        if (spentToday(msg.cardToken()) + amountPence > pence(card.dailyLimit())) {
            return Outcome.decline("LIMIT_EXCEEDED");
        }
        try {
            JsonNode hold = essence.post()
                    .uri("/v1/accounts/{acc}/holds", card.account())
                    .header("Idempotency-Key", msg.messageId())
                    .body(Map.of("amount", Map.of("value", msg.amount(), "currency", msg.currency()),
                            "reference", msg.merchant() == null ? "Card" : msg.merchant().name()))
                    .retrieve()
                    .body(JsonNode.class);
            addSpend(msg.cardToken(), amountPence);
            return new Outcome("APPROVE", null, hold == null ? null : hold.path("holdId").asText(null), null);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 422 && e.getResponseBodyAsString().contains("PAY-FUNDS-001")) {
                return Outcome.decline("INSUFFICIENT_FUNDS");
            }
            return Outcome.decline("CORE_UNAVAILABLE");
        } catch (RestClientException e) {
            // Essence did not answer inside the EHI budget. Prototype stand-in processing (STIP) is decline.
            return Outcome.decline("CORE_UNAVAILABLE");
        }
    }

    private Outcome presentment(EhiMessage msg) {
        Optional<String> holdRef = originalHold(msg.originalMessageId());
        if (holdRef.isEmpty()) {
            return new Outcome("ACK", "NO_MATCHING_AUTHORISATION", null, null);
        }
        try {
            JsonNode posted = essence.post().uri("/v1/holds/{id}/capture", holdRef.get())
                    .body(Map.of("amount", msg.amount())).retrieve().body(JsonNode.class);
            return new Outcome("ACK", null, holdRef.get(), posted == null ? null : posted.path("postingRef").asText(null));
        } catch (RestClientException e) {
            return new Outcome("ACK", "CAPTURE_PENDING", holdRef.get(), null);
        }
    }

    private Outcome reversal(EhiMessage msg, Card card) {
        Optional<String> holdRef = originalHold(msg.originalMessageId());
        holdRef.ifPresent(h -> {
            try {
                essence.delete().uri("/v1/holds/{id}", h).retrieve().toBodilessEntity();
                addSpend(msg.cardToken(), -pence(msg.amount()));
            } catch (RestClientException e) {
                log.warn("ehi.release_failed holdId={} error={}", h, e.toString());
            }
        });
        return new Outcome("ACK", holdRef.isEmpty() ? "NO_MATCHING_AUTHORISATION" : null, holdRef.orElse(null), null);
    }

    // ---------- daily spend per card (pence, in OCI Cache) ----------

    private String spendKey(String token) {
        return "spend:" + token + ":" + LocalDate.now(ZoneOffset.UTC);
    }

    private long spentToday(String token) {
        try {
            String v = redis.opsForValue().get(spendKey(token));
            return v == null ? 0 : Long.parseLong(v);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private void addSpend(String token, long pence) {
        try {
            redis.opsForValue().increment(spendKey(token), pence);
            redis.expire(spendKey(token), Duration.ofDays(2));
        } catch (RuntimeException e) {
            log.warn("spend.update_failed token={} error={}", token, e.toString());
        }
    }

    static long pence(String amount) {
        return new BigDecimal(amount).movePointRight(2).longValueExact();
    }

    // ---------- auth_log and events ----------

    private Optional<Decision> previousDecision(String messageId) {
        return jdbc.sql("SELECT decision, reason, latency_ms FROM auth_log WHERE thredd_msg_id = :id LIMIT 1")
                .param("id", messageId)
                .query((rs, n) -> new Decision(messageId, rs.getString("decision"), rs.getString("reason"),
                        rs.getInt("latency_ms")))
                .optional();
    }

    private Optional<String> originalHold(String originalMessageId) {
        if (originalMessageId == null) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT essence_hold_ref FROM auth_log
                        WHERE thredd_msg_id = :id AND msg_type = 'AUTHORISATION' AND essence_hold_ref IS NOT NULL
                        LIMIT 1""")
                .param("id", originalMessageId)
                .query(String.class)
                .optional();
    }

    private void record(EhiMessage msg, Card card, Outcome outcome, Decision decision, String correlationId) {
        Correlation.set(correlationId);
        try {
            UUID authId = Ids.uuidV7();
            jdbc.sql("""
                            INSERT INTO auth_log (auth_id, correlation_id, thredd_msg_id, msg_type, card_token,
                                                  customer_id, account, amount, currency, merchant, decision, reason,
                                                  essence_hold_ref, essence_posting_ref, latency_ms)
                            VALUES (:authId, :correlationId, :msgId, :type, :token, :customerId, :account, :amount,
                                    :currency, CAST(:merchant AS jsonb), :decision, :reason, :hold, :posting, :latency)""")
                    .param("authId", authId)
                    .param("correlationId", correlationId == null ? "UNKNOWN" : correlationId)
                    .param("msgId", msg.messageId())
                    .param("type", msg.messageType())
                    .param("token", msg.cardToken())
                    .param("customerId", card.customerId() == null ? null : UUID.fromString(card.customerId()))
                    .param("account", card.account())
                    .param("amount", new BigDecimal(msg.amount()))
                    .param("currency", msg.currency())
                    .param("merchant", msg.merchant() == null ? null : mapper.writeValueAsString(msg.merchant()))
                    .param("decision", decision.decision())
                    .param("reason", decision.reason())
                    .param("hold", outcome.holdRef())
                    .param("posting", outcome.postingRef())
                    .param("latency", decision.latencyMs())
                    .update();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("authId", authId.toString());
            data.put("messageId", msg.messageId());
            data.put("cardToken", msg.cardToken());
            data.put("account", card.account());
            data.put("amount", Map.of("value", msg.amount(), "currency", msg.currency()));
            data.put("merchant", msg.merchant() == null ? null : msg.merchant().name());
            data.put("decision", decision.decision());
            data.put("reason", decision.reason());
            data.put("latencyMs", decision.latencyMs());
            String type = switch (msg.messageType()) {
                case "PRESENTMENT" -> "card.settled";
                case "REVERSAL" -> "card.reversed";
                default -> "APPROVE".equals(decision.decision()) ? "card.authorised" : "card.declined";
            };
            events.publish(Topics.CARD_EVENTS, EventEnvelope.of(type, "ehi-responder", correlationId, null,
                    card.customerId() == null ? msg.cardToken() : card.customerId(), data));
        } catch (JsonProcessingException | RuntimeException e) {
            log.error("ehi.record.failed messageId={} error={}", msg.messageId(), e.toString());
        } finally {
            Correlation.clear();
        }
    }

    record Outcome(String decision, String reason, String holdRef, String postingRef) {
        static Outcome decline(String reason) {
            return new Outcome("DECLINE", reason, null, null);
        }
    }

    public record Decision(String messageId, String decision, String reason, int latencyMs) {
    }
}
