package bank.pbp.common.events;

import bank.pbp.common.correlation.Correlation;
import bank.pbp.common.observability.FlowMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Restores the correlation ID from the Kafka header before each listener call, and clears it after.
 * Also counts the record and how long it took to arrive (LLD section 17). The trace itself is continued
 * by the listener observation from the {@code traceparent} header.
 */
public class CorrelationRecordInterceptor implements RecordInterceptor<Object, Object> {

    /** When the event was written: set by EventPublisher, or by Debezium from the outbox row's created_at. */
    public static final String OCCURRED_AT = "occurredAt";

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        Correlation.set(header(record, Correlation.KAFKA_HEADER));
        FlowMetrics.consumed(record.topic(), header(record, "type"));
        FlowMetrics.delivered(record.topic(), instant(header(record, OCCURRED_AT)));
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        Correlation.clear();
    }

    private static String header(ConsumerRecord<Object, Object> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
