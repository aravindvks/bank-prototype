package bank.pbp.mapping;

import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.mapping.model.Customer;
import bank.pbp.mapping.model.ExternalRef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Cache updater (LLD section 11): turns mapping-events into the OCI Cache keys the EHI responder
 * reads. On any change for a customer it rebuilds that customer's card keys from PostgreSQL, so
 * out-of-order events always end in the latest state.
 * <ul>
 *   <li>{@code card:{token}} → {customerId, account, product (DEBIT or CREDIT), cardStatus, customerStatus,
 *       dailyLimit, online, contactless, atm, v}</li>
 *   <li>{@code cust:{customerId}:cards} → set of tokens</li>
 * </ul>
 */
@Component
public class CardCacheUpdater {

    static final String DEFAULT_DAILY_LIMIT = "300.00";
    private static final Logger log = LoggerFactory.getLogger(CardCacheUpdater.class);

    private final StringRedisTemplate redis;
    private final MappingRepository repo;
    private final EventPublisher events;
    private final ObjectMapper mapper;

    public CardCacheUpdater(StringRedisTemplate redis, MappingRepository repo, EventPublisher events,
                            ObjectMapper mapper) {
        this.redis = redis;
        this.repo = repo;
        this.events = events;
        this.mapper = mapper;
    }

    @KafkaListener(topics = Topics.MAPPING_EVENTS, groupId = "cache-updater")
    public void onMappingEvent(String message) {
        EventEnvelope e = events.read(message);
        if (e.customerId() != null) {
            rebuild(UUID.fromString(e.customerId()));
        }
    }

    public void rebuild(UUID customerId) {
        Optional<Customer> customer = repo.findCustomer(customerId);
        if (customer.isEmpty()) {
            return;
        }
        List<ExternalRef> refs = repo.refsForCustomer(customerId);
        int cards = 0;
        for (ExternalRef card : refs) {
            if (!"CARD".equals(card.entityType())) {
                continue;
            }
            Optional<ExternalRef> parent = refs.stream()
                    .filter(r -> r.refId().equals(card.parentRefId()))
                    .findFirst();
            String account = parent.map(ExternalRef::externalId).orElse(null);
            // A credit card hangs off its Thredd credit account; a debit card off the Essence account.
            String product = parent.map(p -> "CREDIT_ACCOUNT".equals(p.entityType()) ? "CREDIT" : "DEBIT")
                    .orElse("DEBIT");
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("customerId", customerId.toString());
            value.put("account", account);
            value.put("product", product);
            value.put("cardStatus", card.status());
            value.put("customerStatus", customer.get().status());
            JsonNode meta = metadata(card);
            value.put("dailyLimit", meta.path("dailyLimit").asText(DEFAULT_DAILY_LIMIT));
            value.put("online", meta.path("online").asBoolean(true));
            value.put("contactless", meta.path("contactless").asBoolean(true));
            value.put("atm", meta.path("atm").asBoolean(true));
            value.put("v", customer.get().version());
            redis.opsForValue().set("card:" + card.externalId(), json(value));
            redis.opsForSet().add("cust:" + customerId + ":cards", card.externalId());
            cards++;
        }
        if (cards > 0) {
            log.info("cache.rebuilt customerId={} cards={} customerStatus={}", customerId, cards,
                    customer.get().status());
        }
    }

    private JsonNode metadata(ExternalRef ref) {
        try {
            return mapper.readTree(ref.metadata() == null ? "{}" : ref.metadata());
        } catch (JsonProcessingException e) {
            return mapper.createObjectNode();
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
