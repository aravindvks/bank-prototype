package bank.pbp.common.observability;

import bank.pbp.common.correlation.Correlation;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;

/**
 * Puts the journey's correlation ID on every span as {@code pbp.correlation_id}, so a correlation ID
 * from a screen, log line or problem+json body finds its trace in Tempo. It is a high-cardinality key:
 * it goes on spans only, never on metrics.
 */
public class CorrelationObservationFilter implements ObservationFilter {

    public static final String KEY = "pbp.correlation_id";

    @Override
    public Observation.Context map(Observation.Context context) {
        Correlation.current().ifPresent(id -> context.addHighCardinalityKeyValue(KeyValue.of(KEY, id)));
        return context;
    }
}
