package bank.pbp.ehi;

import bank.pbp.common.correlation.Correlation;
import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.common.ids.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Authorisation decision order from LLD section 5:
 * duplicate → card lookup → card and customer status → Essence available balance → respond,
 * then log and publish off the response path.
 *
 * <p>Phase 0 differences, closed in Phase 1: the card lookup calls the mapping service instead
 * of OCI Cache, no Essence hold is placed, and card limits are not yet checked.
 */
@Service
public class AuthorisationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorisationService.class);

    private final JdbcClient jdbc;
    private final EventPublisher events;
    private final ObjectMapper mapper;
    private final ExecutorService afterResponse;
    private final RestClient mapping;
    private final RestClient essence;

    public AuthorisationService(JdbcClient jdbc, EventPublisher events, ObjectMapper mapper,
                                ExecutorService afterResponseExecutor, RestClient.Builder builder,
                                @Value("${pbp.ehi.mapping-url}") String mappingUrl,
                                @Value("${pbp.ehi.essence-adapter-url}") String essenceUrl,
                                @Value("${pbp.ehi.lookup-timeout-ms}") int lookupTimeoutMs,
                                @Value("${pbp.ehi.essence-timeout-ms}") int essenceTimeoutMs) {
        this.jdbc = jdbc;
        this.events = events;
        this.mapper = mapper;
        this.afterResponse = afterResponseExecutor;
        this.mapping = builder.clone().baseUrl(mappingUrl).requestFactory(factory(lookupTimeoutMs)).build();
        this.essence = builder.clone().baseUrl(essenceUrl).requestFactory(factory(essenceTimeoutMs)).build();
    }

    public Decision authorise(EhiMessage msg) {
        long start = System.nanoTime();

        Optional<Decision> duplicate = previousDecision(msg.messageId());
        if (duplicate.isPresent()) {
            return duplicate.get();
        }

        Lookup card = lookup(msg.cardToken());
        Decision decision = decide(msg, card);
        int latencyMs = (int) ((System.nanoTime() - start) / 1_000_000);
        Decision timed = new Decision(msg.messageId(), decision.decision(), decision.reason(), latencyMs);

        String correlationId = Correlation.current().orElse(null);
        afterResponse.submit(() -> record(msg, card, timed, correlationId));
        log.info("ehi.decision messageId={} decision={} reason={} latencyMs={}",
                msg.messageId(), timed.decision(), timed.reason(), latencyMs);
        return timed;
    }

    private Decision decide(EhiMessage msg, Lookup card) {
        if (card.error() != null) {
            return Decision.decline(msg.messageId(), card.error());
        }
        if (!"ACTIVE".equals(card.cardStatus())) {
            return Decision.decline(msg.messageId(), "CARD_BLOCKED");
        }
        if (!"ACTIVE".equals(card.customerStatus())) {
            return Decision.decline(msg.messageId(), "CUSTOMER_FROZEN");
        }
        BigDecimal available;
        try {
            JsonNode balance = essence.get()
                    .uri("/v1/accounts/{acc}/balance?lane=ehi", card.account())
                    .retrieve()
                    .body(JsonNode.class);
            available = new BigDecimal(balance.path("available").asText("0"));
        } catch (RestClientException | NullPointerException | NumberFormatException e) {
            // Prototype stand-in processing (STIP) is decline.
            return Decision.decline(msg.messageId(), "CORE_UNAVAILABLE");
        }
        if (available.compareTo(new BigDecimal(msg.amount())) < 0) {
            return Decision.decline(msg.messageId(), "INSUFFICIENT_FUNDS");
        }
        return new Decision(msg.messageId(), "APPROVE", null, 0);
    }

    private Lookup lookup(String token) {
        try {
            JsonNode body = mapping.get()
                    .uri(u -> u.path("/v1/resolve").queryParam("system", "THREDD").queryParam("type", "CARD")
                            .queryParam("id", token).build())
                    .retrieve()
                    .body(JsonNode.class);
            String account = null;
            for (JsonNode parent : body.path("parents")) {
                if ("ACCOUNT".equals(parent.path("entityType").asText())) {
                    account = parent.path("externalId").asText();
                }
            }
            return new Lookup(
                    body.path("customer").path("customerId").asText(null),
                    body.path("customer").path("status").asText(null),
                    body.path("ref").path("status").asText(null),
                    account,
                    account == null ? "CARD_NOT_FOUND" : null);
        } catch (HttpClientErrorException.NotFound e) {
            return Lookup.failed("CARD_NOT_FOUND");
        } catch (RestClientException | NullPointerException e) {
            return Lookup.failed("CORE_UNAVAILABLE");
        }
    }

    private Optional<Decision> previousDecision(String messageId) {
        return jdbc.sql("SELECT decision, reason, latency_ms FROM auth_log WHERE thredd_msg_id = :id LIMIT 1")
                .param("id", messageId)
                .query((rs, n) -> new Decision(messageId, rs.getString("decision"), rs.getString("reason"),
                        rs.getInt("latency_ms")))
                .optional();
    }

    private void record(EhiMessage msg, Lookup card, Decision decision, String correlationId) {
        Correlation.set(correlationId);
        try {
            UUID authId = Ids.uuidV7();
            jdbc.sql("""
                            INSERT INTO auth_log (auth_id, correlation_id, thredd_msg_id, msg_type, card_token,
                                                  customer_id, account, amount, currency, merchant, decision, reason,
                                                  latency_ms)
                            VALUES (:authId, :correlationId, :msgId, :type, :token, :customerId, :account, :amount,
                                    :currency, CAST(:merchant AS jsonb), :decision, :reason, :latency)""")
                    .param("authId", authId)
                    .param("correlationId", correlationId == null ? "UNKNOWN" : correlationId)
                    .param("msgId", msg.messageId())
                    .param("type", msg.messageType())
                    .param("token", msg.cardToken())
                    .param("customerId", card.customerId() == null ? null : UUID.fromString(card.customerId()))
                    .param("account", card.account())
                    .param("amount", new BigDecimal(msg.amount()))
                    .param("currency", msg.currency())
                    .param("merchant", mapper.writeValueAsString(msg.merchant()))
                    .param("decision", decision.decision())
                    .param("reason", decision.reason())
                    .param("latency", decision.latencyMs())
                    .update();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("authId", authId.toString());
            data.put("cardToken", msg.cardToken());
            data.put("account", card.account());
            data.put("amount", Map.of("value", msg.amount(), "currency", msg.currency()));
            data.put("merchant", msg.merchant().name());
            data.put("decision", decision.decision());
            data.put("reason", decision.reason());
            data.put("latencyMs", decision.latencyMs());
            String type = "APPROVE".equals(decision.decision()) ? "card.authorised" : "card.declined";
            events.publish(Topics.CARD_EVENTS, EventEnvelope.of(type, "ehi-responder", correlationId, null,
                    card.customerId() == null ? msg.cardToken() : card.customerId(), data));
        } catch (JsonProcessingException | RuntimeException e) {
            log.error("ehi.record.failed messageId={} error={}", msg.messageId(), e.toString());
        } finally {
            Correlation.clear();
        }
    }

    private static SimpleClientHttpRequestFactory factory(int timeoutMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Math.min(timeoutMs, 500));
        f.setReadTimeout(timeoutMs);
        return f;
    }

    record Lookup(String customerId, String customerStatus, String cardStatus, String account, String error) {
        static Lookup failed(String reason) {
            return new Lookup(null, null, null, null, reason);
        }
    }

    public record Decision(String messageId, String decision, String reason, int latencyMs) {
        static Decision decline(String messageId, String reason) {
            return new Decision(messageId, "DECLINE", reason, 0);
        }
    }
}
