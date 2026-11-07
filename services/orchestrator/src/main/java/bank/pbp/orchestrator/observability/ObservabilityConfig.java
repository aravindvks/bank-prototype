package bank.pbp.orchestrator.observability;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Runs {@link FlowMetricsCollector} on a schedule. */
@Configuration
@EnableScheduling
public class ObservabilityConfig {
}
