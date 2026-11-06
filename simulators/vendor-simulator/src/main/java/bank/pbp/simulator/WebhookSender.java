package bank.pbp.simulator;

import bank.pbp.common.webhook.WebhookSignature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Sends signed vendor webhooks after a delay, the way a real vendor reports asynchronous outcomes. */
@Component
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final RestClient http;
    private final ObjectMapper mapper;
    private final String secret;

    public WebhookSender(RestClient.Builder builder, ObjectMapper mapper,
                         @Value("${sim.webhook-secret}") String secret) {
        this.http = builder.build();
        this.mapper = mapper;
        this.secret = secret;
    }

    public void send(String url, Map<String, Object> payload, long delayMs) {
        scheduler.schedule(() -> post(url, payload), delayMs, TimeUnit.MILLISECONDS);
    }

    private void post(String url, Map<String, Object> payload) {
        try {
            String body = mapper.writeValueAsString(payload);
            long ts = Instant.now().getEpochSecond();
            http.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(WebhookSignature.TIMESTAMP_HEADER, String.valueOf(ts))
                    .header(WebhookSignature.SIGNATURE_HEADER, WebhookSignature.sign(secret, ts, body))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("webhook.sent url={} type={}", url, payload.get("type"));
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("webhook.failed url={} type={} error={}", url, payload.get("type"), e.toString());
        }
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }
}
