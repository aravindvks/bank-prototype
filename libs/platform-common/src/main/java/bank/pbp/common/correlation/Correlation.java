package bank.pbp.common.correlation;

import bank.pbp.common.ids.Ids;
import bank.pbp.common.ids.JourneyType;
import java.util.Optional;
import org.slf4j.MDC;

/**
 * Holds the current correlation ID for the thread in the logging MDC, so every log line,
 * outbound HTTP call and Kafka record of a journey carries the same ID.
 */
public final class Correlation {

    public static final String HEADER = "X-Correlation-ID";
    public static final String KAFKA_HEADER = "correlationId";
    public static final String MDC_KEY = "correlationId";

    private Correlation() {
    }

    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }

    /** Current ID, or a new one of the given journey type when none is set. */
    public static String currentOrNew(JourneyType type) {
        return current().orElseGet(() -> {
            String id = Ids.newCorrelationId(type);
            set(id);
            return id;
        });
    }

    public static void set(String correlationId) {
        if (correlationId == null) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, correlationId);
        }
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
