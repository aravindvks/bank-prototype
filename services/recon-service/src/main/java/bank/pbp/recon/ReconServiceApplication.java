package bank.pbp.recon;

import bank.pbp.common.correlation.Correlation;
import bank.pbp.common.ids.Ids;
import bank.pbp.common.ids.JourneyType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recon service skeleton. Phase 0 creates the nightly run record per source so the schedule,
 * schema and run history are in place; the matching rules in LLD section 8 (Spring Batch,
 * 1,000-line chunks) arrive in Phase 2.
 */
@SpringBootApplication
@EnableScheduling
@RestController
public class ReconServiceApplication {

    private static final Logger log = LoggerFactory.getLogger(ReconServiceApplication.class);
    private static final List<String> SOURCES = List.of("THREDD_SETTLEMENT", "ESSENCE_POSTINGS", "PAYMENTS");

    private final JdbcClient jdbc;

    public ReconServiceApplication(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static void main(String[] args) {
        SpringApplication.run(ReconServiceApplication.class, args);
    }

    @Scheduled(cron = "${pbp.recon.cron}", zone = "UTC")
    public void nightly() {
        run(LocalDate.now(ZoneOffset.UTC).minusDays(1));
    }

    /** Manual trigger for demos and tests. */
    @PostMapping("/v1/recon/runs")
    public Map<String, Object> trigger() {
        return Map.of("runs", run(LocalDate.now(ZoneOffset.UTC).minusDays(1)));
    }

    @GetMapping("/v1/recon/runs")
    public List<Map<String, Object>> runs() {
        return jdbc.sql("SELECT run_id, source, run_date, status, started_at FROM recon_run ORDER BY started_at DESC LIMIT 50")
                .query()
                .listOfRows();
    }

    private int run(LocalDate runDate) {
        Correlation.set(Ids.newCorrelationId(JourneyType.REC));
        try {
            int created = 0;
            for (String source : SOURCES) {
                created += jdbc.sql("""
                                INSERT INTO recon_run (run_id, source, run_date, lines_total, matched, exceptions,
                                                       status, started_at, ended_at)
                                VALUES (:id, :source, :runDate, 0, 0, 0, 'NOT_IMPLEMENTED', :now, :now)
                                ON CONFLICT (source, run_date) DO NOTHING""")
                        .param("id", Ids.uuidV7())
                        .param("source", source)
                        .param("runDate", runDate)
                        .param("now", OffsetDateTime.now(ZoneOffset.UTC))
                        .update();
            }
            log.info("recon.run runDate={} created={}", runDate, created);
            return created;
        } finally {
            Correlation.clear();
        }
    }
}
