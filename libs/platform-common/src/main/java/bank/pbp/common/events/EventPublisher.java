package bank.pbp.common.events;

import bank.pbp.common.correlation.Correlation;
import bank.pbp.common.observability.FlowMetrics;
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
 * <p>Two modes ({@code pbp.events.mode}):
 * <ul>
 *   <li>{@code outbox}: the event is written to the service's own {@code outbox} table in the caller's
 *       transaction and Debezium relays it to Kafka (LLD section 9). The data change and the event
 *       commit or roll back together.</li>
 *   <li>{@code direct}: published to Kafka at once (services without a database, and IDE runs
 *       without Debezium).</li>
 * </ul>
 */
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final OutboxWriter outbox;

    public EventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this(kafka, mapper, null);
    }

    public EventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper, OutboxWriter outbox) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.outbox = outbox;
    }

    public void publish(String topic, EventEnvelope event) {
        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Event cannot be serialised: " + event.type(), e);
        }
        if (outbox != null) {
            outbox.write(topic, event, json);
            FlowMetrics.published(topic, event.type());
            log.info("event.outboxed topic={} type={} eventId={}", topic, event.type(), event.eventId());
            return;
        }
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, event.customerId(), json);
        if (event.correlationId() != null) {
            record.headers().add(Correlation.KAFKA_HEADER, event.correlationId().getBytes(StandardCharsets.UTF_8));
        }
        record.headers().add("type", event.type().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", event.eventId().getBytes(StandardCharsets.UTF_8));
        if (event.occurredAt() != null) {
            record.headers().add(CorrelationRecordInterceptor.OCCURRED_AT,
                    event.occurredAt().toString().getBytes(StandardCharsets.UTF_8));
        }
        // traceparent is added by the KafkaTemplate observation (spring.kafka.template.observation-enabled).
        try {
            kafka.send(record).get(5, TimeUnit.SECONDS);
            FlowMetrics.published(topic, event.type());
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
