package bank.pbp.ehi;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reconciliation source (LLD section 8): card presentments captured in Essence on a business date. */
@RestController
public class ReconSourceController {

    private final JdbcClient jdbc;

    public ReconSourceController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/recon/presentments")
    public List<Map<String, Object>> presentments(@RequestParam LocalDate date) {
        return jdbc.sql("""
                        SELECT thredd_msg_id AS "messageId", card_token AS "cardToken", amount::text AS amount,
                               essence_posting_ref AS "postingRef", correlation_id AS "correlationId"
                        FROM auth_log
                        WHERE msg_type = 'PRESENTMENT' AND (at AT TIME ZONE 'UTC')::date = :date""")
                .param("date", date)
                .query()
                .listOfRows();
    }
}
