package bank.pbp.common.events;

import bank.pbp.common.correlation.Correlation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publishes an {@link EventEnvelope} keyed by customer ID, repeating correlationId and type
 * as Kafka headers so consumers can route and trace without parsing the body.
 *
 * <p>Phase 0 publishes directly and waits for the broker acknowledgement. The transactional
 * outbox relayed by Debezium (LLD section 9) replaces this in Phase 1; callers do not change.
 */
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public EventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.kafka = kafka;
        this.mapper = mapper;
    }

    public void publish(String topic, EventEnvelope event) {
        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Event cannot be serialised: " + event.type(), e);
        }
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, event.customerId(), json);
        if (event.correlationId() != null) {
            record.headers().add(Correlation.KAFKA_HEADER, event.correlationId().getBytes(StandardCharsets.UTF_8));
        }
        record.headers().add("type", event.type().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", event.eventId().getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).get(5, TimeUnit.SECONDS);
            log.info("event.published topic={} type={} eventId={}", topic, event.type(), event.eventId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing " + event.type(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka did not acknowledge " + event.type() + " on " + topic, e);
        }
    }

    public EventEnvelope read(String json) {
        try {
            return mapper.readValue(json, EventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Message is not a valid event envelope", e);
        }
    }
}
