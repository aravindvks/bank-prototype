package bank.pbp.common.observability;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * The shared metric names behind the flow dashboards and alerts (LLD section 15). Recorded on
 * Micrometer's global registry, which Spring Boot connects to Prometheus; in unit tests it is a no-op.
 *
 * <p>Labels stay low-cardinality: event types, statuses and reason codes only. Customer, payment and
 * correlation IDs never become labels; they belong on spans and log lines.
 */
public final class FlowMetrics {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.:-]{1,48}");

    private FlowMetrics() {
    }

    /** Every event written to Kafka or the outbox: {@code pbp_events_published_total{topic,type}}. */
    public static void published(String topic, String type) {
        Metrics.counter("pbp.events.published", "topic", topic, "type", label(type)).increment();
    }

    /** Every record a listener receives: {@code pbp_events_consumed_total{topic,type}}. */
    public static void consumed(String topic, String type) {
        Metrics.counter("pbp.events.consumed", "topic", topic, "type", label(type)).increment();
    }

    /** Time from the event being written to a listener receiving it: outbox, Debezium and Kafka together. */
    public static void delivered(String topic, Instant occurredAt) {
        if (occurredAt == null) {
            return;
        }
        Duration lag = Duration.between(occurredAt, Instant.now());
        if (!lag.isNegative()) {
            Timer.builder("pbp.events.delivery").tag("topic", topic).register(Metrics.globalRegistry).record(lag);
        }
    }

    /** A bounded label value: codes pass through, anything free-text becomes OTHER, null becomes NONE. */
    public static String label(String value) {
        if (value == null || value.isBlank()) {
            return "NONE";
        }
        return CODE.matcher(value).matches() ? value : "OTHER";
    }
}
