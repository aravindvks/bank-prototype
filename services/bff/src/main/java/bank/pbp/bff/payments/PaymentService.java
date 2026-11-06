package bank.pbp.bff.payments;

import bank.pbp.common.correlation.Correlation;
import bank.pbp.common.error.PbpException;
import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.common.ids.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts a payment: duplicate protection on Idempotency-Key, then a payment.create command on
 * payment-commands keyed by customer ID. No business decision is taken here.
 */
@Service
public class PaymentService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final EventPublisher events;
    private final ObjectMapper mapper;

    public PaymentService(JdbcClient jdbc, EventPublisher events, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.events = events;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> accept(UUID customerId, String idempotencyKey, PaymentRequest request) {
        String key = customerId + ":" + idempotencyKey;
        String hash = sha256(toJson(request));

        Optional<Map<String, Object>> replay = replay(key, hash);
        if (replay.isPresent()) {
            return replay.get();
        }

        String paymentId = Ids.uuidV7().toString();
        String correlationId = Correlation.current().orElseThrow();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("paymentId", paymentId);
        response.put("status", "ACCEPTED");
        response.put("correlationId", correlationId);

        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_key (key, request_hash, response)
                        VALUES (:key, :hash, CAST(:response AS jsonb))
                        ON CONFLICT (key) DO NOTHING""")
                .param("key", key).param("hash", hash).param("response", toJson(response))
                .update();
        if (inserted == 0) {
            // Another request with the same key won the race.
            return replay(key, hash).orElseThrow();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", paymentId);
        data.put("customerId", customerId.toString());
        data.put("debtorAccount", request.debtorAccount());
        data.put("creditor", Map.of(
                "name", request.creditor().name(),
                "sortCode", request.creditor().sortCode(),
                "accountNumber", request.creditor().accountNumber()));
        data.put("amount", Map.of("value", request.amount().value(), "currency", request.amount().currency()));
        data.put("purpose", request.purpose());
        data.put("remittanceInfo", request.remittanceInfo());
        data.put("copResult", request.copResult());
        data.put("idempotencyKey", key);
        // Microsecond precision matches PostgreSQL timestamptz, so the orchestrator can use it as a key.
        data.put("initiatedAt", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());

        // Kafka acknowledgement happens inside the transaction: if it fails, the key is rolled back
        // and the client can retry. Phase 1 moves this to the outbox relayed by Debezium.
        events.publish(Topics.PAYMENT_COMMANDS,
                EventEnvelope.of("payment.create", "bff", correlationId, null, customerId.toString(), data));
        return response;
    }

    private Optional<Map<String, Object>> replay(String key, String hash) {
        return jdbc.sql("SELECT request_hash, response::text AS response FROM idempotency_key WHERE key = :key")
                .param("key", key)
                .query((rs, n) -> {
                    if (!hash.equals(rs.getString("request_hash"))) {
                        throw new PbpException("GEN-IDEMP-001", 409, "Idempotency key reused",
                                "This Idempotency-Key was already used with a different request body");
                    }
                    return fromJson(rs.getString("response"));
                })
                .optional();
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> fromJson(String json) {
        try {
            return mapper.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
