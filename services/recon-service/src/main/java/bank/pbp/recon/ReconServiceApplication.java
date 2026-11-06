package bank.pbp.recon;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Recon service (LLD section 8): nightly at 02:00 UTC for the previous day, or on demand. */
@SpringBootApplication
@EnableScheduling
@RestController
public class ReconServiceApplication {

    private final ReconEngine engine;
    private final JdbcClient jdbc;

    public ReconServiceApplication(ReconEngine engine, JdbcClient jdbc) {
        this.engine = engine;
        this.jdbc = jdbc;
    }

    public static void main(String[] args) {
        SpringApplication.run(ReconServiceApplication.class, args);
    }

    @Scheduled(cron = "${pbp.recon.cron}", zone = "UTC")
    public void nightly() {
        engine.run(LocalDate.now(ZoneOffset.UTC).minusDays(1));
    }

    /** Demo and operations trigger; defaults to today so a demo can reconcile what just happened. */
    @PostMapping("/v1/recon/runs")
    public Map<String, Object> trigger(@RequestParam(required = false) LocalDate date) {
        return engine.run(date == null ? LocalDate.now(ZoneOffset.UTC) : date);
    }

    @GetMapping("/v1/recon/runs")
    public List<Map<String, Object>> runs() {
        return jdbc.sql("""
                        SELECT run_id::text AS "runId", source, run_date::text AS "runDate", lines_total AS "linesTotal",
                               matched, exceptions, status, ended_at::text AS "endedAt"
                        FROM recon_run ORDER BY ended_at DESC NULLS LAST LIMIT 30""")
                .query().listOfRows();
    }

    @GetMapping("/v1/recon/exceptions")
    public List<Map<String, Object>> exceptions(@RequestParam(defaultValue = "OPEN") String status) {
        return jdbc.sql("""
                        SELECT exception_id::text AS "exceptionId", type, source_ref AS "sourceRef", amount::text AS amount,
                               owner_queue AS "ownerQueue", status, resolution, run_date::text AS "runDate"
                        FROM recon_exception WHERE status = :status ORDER BY run_date DESC, type LIMIT 200""")
                .param("status", status).query().listOfRows();
    }
}
