package bank.pbp.orchestrator.payments;

import bank.pbp.common.correlation.Correlation;
import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.failure.ApplicationFailure;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class PaymentActivitiesImpl implements PaymentActivities {

    private static final Logger log = LoggerFactory.getLogger(PaymentActivitiesImpl.class);
    private static final Set<String> FINAL = Set.of("COMPLETED", "REJECTED", "FAILED", "RETURNED");
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final EventPublisher events;
    private final ObjectMapper mapper;
    private final RestClient essence;
    private final BigDecimal singleLimit;

    public PaymentActivitiesImpl(JdbcClient jdbc, TransactionTemplate tx, EventPublisher events, ObjectMapper mapper,
                                 RestClient.Builder builder,
                                 @Value("${pbp.adapters.essence-url}") String essenceUrl,
                                 @Value("${pbp.payments.single-limit}") BigDecimal singleLimit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(1000);
        f.setReadTimeout(12_000);
        this.essence = builder.clone().baseUrl(essenceUrl).requestFactory(f).build();
        this.singleLimit = singleLimit;
    }

    @Override
    public void createPayment(PaymentCommand c) {
        withCorrelation(c, () -> tx.execute(status -> {
            Map<String, String> creditor = Map.of("name", c.creditorName(), "sortCode", c.creditorSortCode(),
                    "accountNumber", c.creditorAccountNumber());
            int inserted = jdbc.sql("""
                            INSERT INTO payment (payment_id, correlation_id, customer_id, debit_account, creditor,
                                                 amount, currency, payment_type, status, idempotency_key, created_at)
                            VALUES (:id, :correlationId, :customerId, :debit, CAST(:creditor AS jsonb), :amount,
                                    :currency, :type, 'INITIATED', :idem, :createdAt)
                            ON CONFLICT DO NOTHING""")
                    .param("id", UUID.fromString(c.paymentId()))
                    .param("correlationId", c.correlationId())
                    .param("customerId", UUID.fromString(c.customerId()))
                    .param("debit", c.debtorAccount())
                    .param("creditor", json(creditor))
                    .param("amount", new BigDecimal(c.amount()))
                    .param("currency", c.currency())
                    .param("type", c.paymentType())
                    .param("idem", c.idempotencyKey())
                    .param("createdAt", OffsetDateTime.parse(c.initiatedAt()))
                    .update();
            if (inserted == 1) {
                jdbc.sql("""
                                INSERT INTO saga (saga_id, saga_type, customer_id, correlation_id, current_step, status)
                                VALUES (:id, 'PAYMENT', :customerId, :correlationId, 'INITIATED', 'RUNNING')
                                ON CONFLICT DO NOTHING""")
                        .param("id", UUID.fromString(c.paymentId()))
                        .param("customerId", UUID.fromString(c.customerId()))
                        .param("correlationId", c.correlationId())
                        .update();
                jdbc.sql("""
                                INSERT INTO payment_status_history (payment_id, from_status, to_status, actor, correlation_id)
                                VALUES (:id, NULL, 'INITIATED', 'svc:orchestrator', :correlationId)""")
                        .param("id", UUID.fromString(c.paymentId()))
                        .param("correlationId", c.correlationId())
                        .update();
                log.info("payment.initiated paymentId={} type={} amount={} {}", c.paymentId(), c.paymentType(),
                        c.amount(), c.currency());
            }
            return null;
        }));
        publish(c, "payment.initiated", "INITIATED", null);
    }

    @Override
    public RiskResult riskCheck(PaymentCommand c) {
        return withCorrelation(c, () -> {
            BigDecimal amount = new BigDecimal(c.amount());
            if (amount.signum() <= 0) {
                return new RiskResult(false, "GEN-VALID-001", "Amount must be greater than zero");
            }
            if (amount.compareTo(singleLimit) > 0) {
                return new RiskResult(false, "PAY-LIMIT-001",
                        "Requested " + c.amount() + " GBP exceeds the single-payment limit " + singleLimit.toPlainString() + " GBP");
            }
            return new RiskResult(true, null, null);
        });
    }

    @Override
    public void transition(PaymentCommand c, String from, String to, String reason) {
        boolean moved = withCorrelation(c, () -> Boolean.TRUE.equals(tx.execute(status -> {
            int rows = jdbc.sql("""
                            UPDATE payment SET status = :to
                            WHERE payment_id = :id AND created_at = :createdAt AND status = :from""")
                    .param("to", to)
                    .param("id", UUID.fromString(c.paymentId()))
                    .param("createdAt", OffsetDateTime.parse(c.initiatedAt()))
                    .param("from", from)
                    .update();
            if (rows == 0) {
                return false;
            }
            jdbc.sql("""
                            INSERT INTO payment_status_history (payment_id, from_status, to_status, reason, actor,
                                                                correlation_id)
                            VALUES (:id, :from, :to, :reason, 'svc:orchestrator', :correlationId)""")
                    .param("id", UUID.fromString(c.paymentId()))
                    .param("from", from)
                    .param("to", to)
                    .param("reason", reason)
                    .param("correlationId", c.correlationId())
                    .update();
            jdbc.sql("""
                            UPDATE saga SET current_step = :to, updated_at = now(),
                                   status = CASE WHEN :final THEN (CASE WHEN :to = 'COMPLETED' THEN 'COMPLETED' ELSE 'FAILED' END)
                                                 ELSE status END
                            WHERE saga_id = :id""")
                    .param("to", to)
                    .param("final", FINAL.contains(to))
                    .param("id", UUID.fromString(c.paymentId()))
                    .update();
            return true;
        })));
        if (moved) {
            log.info("payment.status paymentId={} {} -> {} reason={}", c.paymentId(), from, to, reason);
            publish(c, "payment." + to.toLowerCase(), to, reason);
        }
    }

    @Override
    public String postInternalTransfer(PaymentCommand c) {
        return withCorrelation(c, () -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("debitAccount", c.debtorAccount());
            body.put("creditor", Map.of("name", c.creditorName(), "sortCode", c.creditorSortCode(),
                    "accountNumber", c.creditorAccountNumber()));
            body.put("amount", Map.of("value", c.amount(), "currency", c.currency()));
            body.put("reference", c.remittanceInfo());
            body.put("idempotencyKey", c.paymentId());
            try {
                Map<String, Object> result = essence.post()
                        .uri("/v1/transfers")
                        .body(body)
                        .retrieve()
                        .body(MAP);
                return String.valueOf(result == null ? null : result.get("postingRef"));
            } catch (RestClientResponseException e) {
                // The adapter answers with the LLD problem envelope; its retryable flag decides.
                JsonNode problem = problem(e.getResponseBodyAsString());
                String code = problem.path("code").asText("VND-REJECT-001");
                String detail = problem.path("detail").asText("Rejected by Essence");
                boolean retryable = problem.path("retryable").asBoolean(e.getStatusCode().is5xxServerError());
                if (!retryable) {
                    throw ApplicationFailure.newNonRetryableFailure(detail, code);
                }
                throw ApplicationFailure.newFailure(detail, code);
            }
        });
    }

    private void publish(PaymentCommand c, String type, String status, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", c.paymentId());
        data.put("debtorAccount", c.debtorAccount());
        data.put("amount", Map.of("value", c.amount(), "currency", c.currency()));
        data.put("type", c.paymentType());
        data.put("status", status);
        data.put("reason", reason);
        withCorrelation(c, () -> {
            events.publish(Topics.PAYMENT_EVENTS,
                    EventEnvelope.of(type, "orchestrator", c.correlationId(), null, c.customerId(), data));
            return null;
        });
    }

    private JsonNode problem(String body) {
        try {
            return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (JsonProcessingException e) {
            return mapper.createObjectNode();
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Activities run on Temporal threads, so the correlation ID is restored explicitly for logs and calls. */
    private static <T> T withCorrelation(PaymentCommand c, Supplier<T> work) {
        Correlation.set(c.correlationId());
        try {
            return work.get();
        } finally {
            Correlation.clear();
        }
    }
}
