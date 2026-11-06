package bank.pbp.common.events;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes an event to the service's {@code outbox} table (in the schema of the service's
 * connection) and deletes it again in the same transaction. Debezium's outbox event router reads
 * the insert from the write-ahead log and publishes the payload to the row's topic, keyed by
 * aggregate_id, with type, correlationId and eventId as Kafka headers.
 */
public class OutboxWriter {

    private final JdbcTemplate jdbc;

    public OutboxWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void write(String topic, EventEnvelope event, String json) {
        Long id = jdbc.queryForObject("""
                        INSERT INTO outbox (aggregate_id, topic, event_type, payload, headers, event_id, correlation_id)
                        VALUES (?, ?, ?, CAST(? AS jsonb), '{}'::jsonb, ?, ?) RETURNING id""",
                Long.class, event.customerId(), topic, event.type(), json, event.eventId(), event.correlationId());
        jdbc.update("DELETE FROM outbox WHERE id = ?", id);
    }
}
