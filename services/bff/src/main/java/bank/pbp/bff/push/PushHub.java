package bank.pbp.bff.push;

import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live push to the customer app (HLD: real-time UI). Every BFF instance reads the event topics
 * in its own consumer group (from the latest offset), and forwards each event to the browsers of
 * the customer it belongs to over Server-Sent Events.
 *
 * <p>The customer is taken from the query string because browsers' EventSource cannot send
 * headers; login (Phase 2 F) replaces it with the session.
 */
@RestController
public class PushHub {

    private static final Logger log = LoggerFactory.getLogger(PushHub.class);

    private final Map<String, List<SseEmitter>> byCustomer = new ConcurrentHashMap<>();
    private final EventPublisher events;

    public PushHub(EventPublisher events) {
        this.events = events;
    }

    @GetMapping(path = "/v1/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@RequestParam UUID customerId) {
        String key = customerId.toString();
        SseEmitter emitter = new SseEmitter(0L);
        byCustomer.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> byCustomer.getOrDefault(key, new CopyOnWriteArrayList<>()).remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("ready").data(Map.of("customerId", key)));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    @KafkaListener(groupId = "bff-push-${random.uuid}",
            topics = {Topics.PAYMENT_EVENTS, Topics.CARD_EVENTS, Topics.ONBOARDING_EVENTS, Topics.MAPPING_EVENTS},
            properties = {"auto.offset.reset=latest"})
    public void onEvent(String message) {
        EventEnvelope e = events.read(message);
        if (e.customerId() == null) {
            return;
        }
        List<SseEmitter> emitters = byCustomer.get(e.customerId());
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", e.type());
        payload.put("correlationId", e.correlationId());
        payload.put("occurredAt", e.occurredAt() == null ? null : e.occurredAt().toString());
        payload.put("data", e.data());
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("bank").id(e.eventId()).data(payload, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException ex) {
                emitters.remove(emitter);
            }
        }
        log.debug("push.sent customerId={} type={} clients={}", e.customerId(), e.type(), emitters.size());
    }

    /** Keeps idle connections open through proxies and load balancers. */
    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        byCustomer.values().forEach(list -> list.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException ex) {
                list.remove(emitter);
            }
        }));
    }
}
