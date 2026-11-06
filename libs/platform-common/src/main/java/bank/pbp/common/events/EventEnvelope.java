package bank.pbp.common.events;

import bank.pbp.common.ids.Ids;
import java.time.Instant;
import java.util.Map;

/**
 * The one event envelope used on every Kafka topic (LLD section 9).
 *
 * @param customerId also used as the record key, for per-customer ordering
 */
public record EventEnvelope(
        String eventId,
        String type,
        int schemaVersion,
        Instant occurredAt,
        String producer,
        String correlationId,
        String causationId,
        String customerId,
        Map<String, Object> data) {

    public static EventEnvelope of(String type, String producer, String correlationId, String causationId,
                                   String customerId, Map<String, Object> data) {
        return new EventEnvelope(Ids.uuidV7().toString(), type, 1, Instant.now(), producer,
                correlationId, causationId, customerId, data);
    }
}
