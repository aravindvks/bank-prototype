package bank.pbp.ehi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Card lookup order from LLD sections 5 and 11: in-process copy (5 s) → OCI Cache
 * {@code card:{token}} → fallback. The fallback calls the mapping service; in OCI it reads the
 * PostgreSQL replica. Both unavailable → CORE_UNAVAILABLE.
 */
@Component
public class CardLookup {

    private static final Logger log = LoggerFactory.getLogger(CardLookup.class);
    static final String DEFAULT_DAILY_LIMIT = "300.00";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final RestClient mapping;
    private final long localTtlMs;
    private final Map<String, Local> local = new ConcurrentHashMap<>();

    public CardLookup(StringRedisTemplate redis, ObjectMapper mapper, RestClient.Builder builder,
                      @Value("${pbp.ehi.mapping-url}") String mappingUrl,
                      @Value("${pbp.ehi.lookup-timeout-ms}") int timeoutMs,
                      @Value("${pbp.ehi.local-cache-ms:5000}") long localTtlMs) {
        this.redis = redis;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Math.min(timeoutMs, 500));
        f.setReadTimeout(timeoutMs);
        this.mapping = builder.clone().baseUrl(mappingUrl).requestFactory(f).build();
        this.localTtlMs = localTtlMs;
    }

    public Card find(String token) {
        Local hit = local.get(token);
        if (hit != null && hit.expiresAt > System.currentTimeMillis()) {
            return hit.card;
        }
        Card card = fromCache(token);
        if (card == null) {
            card = fromMapping(token);
        }
        if (card.error() == null) {
            local.put(token, new Local(card, System.currentTimeMillis() + localTtlMs));
        }
        return card;
    }

    private Card fromCache(String token) {
        try {
            String json = redis.opsForValue().get("card:" + token);
            if (json == null) {
                return null;
            }
            JsonNode n = mapper.readTree(json);
            return new Card(n.path("customerId").asText(null), n.path("customerStatus").asText(null),
                    n.path("cardStatus").asText(null), n.path("account").asText(null),
                    n.path("dailyLimit").asText(DEFAULT_DAILY_LIMIT), n.path("online").asBoolean(true),
                    n.path("contactless").asBoolean(true), n.path("atm").asBoolean(true), "cache", null);
        } catch (Exception e) {
            log.warn("cache.unavailable error={}", e.toString());
            return null;
        }
    }

    private Card fromMapping(String token) {
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
            JsonNode ref = body.path("ref");
            return new Card(body.path("customer").path("customerId").asText(null),
                    body.path("customer").path("status").asText(null),
                    ref.path("status").asText(null), account,
                    ref.path("metadata").path("dailyLimit").asText(DEFAULT_DAILY_LIMIT),
                    ref.path("metadata").path("online").asBoolean(true),
                    ref.path("metadata").path("contactless").asBoolean(true),
                    ref.path("metadata").path("atm").asBoolean(true), "mapping",
                    account == null ? "CARD_NOT_FOUND" : null);
        } catch (HttpClientErrorException.NotFound e) {
            return Card.failed("CARD_NOT_FOUND");
        } catch (RuntimeException e) {
            return Card.failed("CORE_UNAVAILABLE");
        }
    }

    public record Card(String customerId, String customerStatus, String cardStatus, String account,
                       String dailyLimit, boolean online, boolean contactless, boolean atm, String source,
                       String error) {
        static Card failed(String reason) {
            return new Card(null, null, null, null, DEFAULT_DAILY_LIMIT, true, true, true, "none", reason);
        }

        /** Whether the card's controls allow this channel (online, contactless, atm; others always allowed). */
        boolean allows(String channel) {
            if (channel == null) {
                return true;
            }
            return switch (channel) {
                case "online" -> online;
                case "contactless" -> contactless;
                case "atm" -> atm;
                default -> true;
            };
        }
    }

    private record Local(Card card, long expiresAt) {
    }
}
