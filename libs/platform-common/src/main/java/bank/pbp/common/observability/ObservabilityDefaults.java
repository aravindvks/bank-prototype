package bank.pbp.common.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Observability defaults every service gets from platform-common (LLD section 17), added with the
 * lowest precedence so a service's application.yml or an environment variable can still override them.
 *
 * <ul>
 *   <li>Traces: W3C trace context, every request sampled unless {@code PBP_TRACE_SAMPLING} says
 *       otherwise, exported over OTLP only when {@code OTEL_EXPORTER_OTLP_ENDPOINT} is set (so IDE runs
 *       and unit tests export nothing).</li>
 *   <li>Kafka: producer and listener observations on, so trace context rides in record headers.</li>
 *   <li>Metrics: tagged with the application name; latency histograms for the timers the
 *       dashboards and alerts use.</li>
 *   <li>Logs: one line format with service, trace ID, span ID and correlation ID, which Loki parses.</li>
 * </ul>
 */
public class ObservabilityDefaults implements EnvironmentPostProcessor {

    static final String SOURCE = "pbpObservabilityDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("management.tracing.sampling.probability", env.getProperty("PBP_TRACE_SAMPLING", "1.0"));
        p.put("management.tracing.propagation.type", "w3c");
        String otlp = env.getProperty("OTEL_EXPORTER_OTLP_ENDPOINT");
        if (otlp != null && !otlp.isBlank()) {
            p.put("management.otlp.tracing.endpoint", stripSlash(otlp) + "/v1/traces");
        }
        p.put("management.opentelemetry.resource-attributes.deployment.environment",
                env.getProperty("PBP_ENV", "local"));
        p.put("management.metrics.tags.application", "${spring.application.name:unknown}");
        for (String timer : new String[] {"http.server.requests", "http.client.requests",
                "spring.kafka.listener", "pbp.ehi.decision", "pbp.events.delivery", "pbp.payment.completion"}) {
            p.put("management.metrics.distribution.percentiles-histogram." + timer, "true");
        }
        p.put("management.endpoints.web.exposure.include", "health,info,prometheus");
        p.put("spring.kafka.template.observation-enabled", "true");
        p.put("spring.kafka.listener.observation-enabled", "true");
        p.put("logging.pattern.level",
                "%5p [${spring.application.name:-},trace=%X{traceId:-},span=%X{spanId:-},cid=%X{correlationId:-}]");
        env.getPropertySources().addLast(new MapPropertySource(SOURCE, p));
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
