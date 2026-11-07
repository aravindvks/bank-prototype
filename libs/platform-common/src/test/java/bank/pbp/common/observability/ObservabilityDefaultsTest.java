package bank.pbp.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class ObservabilityDefaultsTest {

    @Test
    void exportsTracesOnlyWhenACollectorIsConfigured() {
        StandardEnvironment env = new StandardEnvironment();
        new ObservabilityDefaults().postProcessEnvironment(env, null);
        assertThat(env.getProperty("management.otlp.tracing.endpoint")).isNull();
        assertThat(env.getProperty("spring.kafka.listener.observation-enabled")).isEqualTo("true");

        StandardEnvironment withCollector = new StandardEnvironment();
        withCollector.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-collector:4318/")));
        new ObservabilityDefaults().postProcessEnvironment(withCollector, null);
        assertThat(withCollector.getProperty("management.otlp.tracing.endpoint"))
                .isEqualTo("http://otel-collector:4318/v1/traces");
    }

    @Test
    void serviceSettingsWinOverDefaults() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("app",
                Map.of("management.tracing.sampling.probability", "0.1")));
        new ObservabilityDefaults().postProcessEnvironment(env, null);
        assertThat(env.getProperty("management.tracing.sampling.probability")).isEqualTo("0.1");
    }

    @Test
    void labelsStayBounded() {
        assertThat(FlowMetrics.label("NO_MATCH")).isEqualTo("NO_MATCH");
        assertThat(FlowMetrics.label("payment.completed")).isEqualTo("payment.completed");
        assertThat(FlowMetrics.label("Rejected by compliance: looks like fraud")).isEqualTo("OTHER");
        assertThat(FlowMetrics.label(null)).isEqualTo("NONE");
    }
}
