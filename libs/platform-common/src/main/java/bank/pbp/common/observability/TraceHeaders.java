package bank.pbp.common.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;

/**
 * The current span's W3C {@code traceparent}, for hops that Spring does not instrument itself: the
 * outbox row that Debezium turns into a Kafka record header.
 */
public class TraceHeaders {

    public static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    public TraceHeaders(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /**
     * The current traceparent, or an empty string when nothing is being traced. Never null: Debezium copies
     * the column into a header, and an empty value is simply ignored by the consumer's propagator.
     */
    public String traceparent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return "";
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        return carrier.getOrDefault(TRACEPARENT, "");
    }
}
