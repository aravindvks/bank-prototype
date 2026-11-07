package bank.pbp.recon;

import bank.pbp.common.observability.FlowMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reconciliation for the dashboards and alerts (LLD section 15):
 * {@code pbp_recon_exceptions_open{type,queue}} and, per source, the last run's
 * {@code pbp_recon_last_run_matched}, {@code pbp_recon_last_run_exceptions} and
 * {@code pbp_recon_last_run_age_seconds}.
 */
@Component
public class ReconMetrics {

    private static final Logger log = LoggerFactory.getLogger(ReconMetrics.class);

    private final JdbcClient jdbc;
    private final MultiGauge openExceptions;
    private final MultiGauge lastMatched;
    private final MultiGauge lastExceptions;
    private final MultiGauge lastAge;

    public ReconMetrics(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.openExceptions = MultiGauge.builder("pbp.recon.exceptions.open").register(registry);
        this.lastMatched = MultiGauge.builder("pbp.recon.last.run.matched").register(registry);
        this.lastExceptions = MultiGauge.builder("pbp.recon.last.run.exceptions").register(registry);
        this.lastAge = MultiGauge.builder("pbp.recon.last.run.age").baseUnit("seconds").register(registry);
    }

    @Scheduled(initialDelay = 10_000, fixedDelayString = "${pbp.observability.collect-ms:30000}")
    public void collect() {
        try {
            List<MultiGauge.Row<?>> open = new ArrayList<>();
            jdbc.sql("SELECT type, owner_queue, count(*) FROM recon_exception WHERE status = 'OPEN' GROUP BY 1, 2")
                    .query((rs, n) -> open.add(MultiGauge.Row.of(Tags.of("type", FlowMetrics.label(rs.getString(1)),
                            "queue", FlowMetrics.label(rs.getString(2))), rs.getLong(3)))).list();
            openExceptions.register(open, true);

            List<MultiGauge.Row<?>> matched = new ArrayList<>();
            List<MultiGauge.Row<?>> exceptions = new ArrayList<>();
            List<MultiGauge.Row<?>> age = new ArrayList<>();
            jdbc.sql("""
                            SELECT DISTINCT ON (source) source, coalesce(matched, 0), coalesce(exceptions, 0),
                                   extract(epoch FROM now() - coalesce(ended_at, started_at))
                            FROM recon_run ORDER BY source, ended_at DESC NULLS LAST""")
                    .query((rs, n) -> {
                        Tags tags = Tags.of("source", FlowMetrics.label(rs.getString(1)));
                        matched.add(MultiGauge.Row.of(tags, rs.getLong(2)));
                        exceptions.add(MultiGauge.Row.of(tags, rs.getLong(3)));
                        age.add(MultiGauge.Row.of(tags, rs.getDouble(4)));
                        return null;
                    }).list();
            lastMatched.register(matched, true);
            lastExceptions.register(exceptions, true);
            lastAge.register(age, true);
        } catch (RuntimeException e) {
            log.warn("recon.metrics.failed error={}", e.toString());
        }
    }
}
