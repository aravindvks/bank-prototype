package bank.pbp.common.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

    private static final String SECRET = "local-dev-secret";
    private static final String BODY = "{\"type\":\"credit.received\"}";

    @Test
    void acceptsAFreshCorrectSignature() {
        long now = Instant.now().getEpochSecond();
        String sig = WebhookSignature.sign(SECRET, now, BODY);
        assertThat(WebhookSignature.verify(SECRET, String.valueOf(now), BODY, sig, 300)).isTrue();
    }

    @Test
    void rejectsTamperedBodyWrongSecretAndOldTimestamp() {
        long now = Instant.now().getEpochSecond();
        String sig = WebhookSignature.sign(SECRET, now, BODY);
        assertThat(WebhookSignature.verify(SECRET, String.valueOf(now), BODY + " ", sig, 300)).isFalse();
        assertThat(WebhookSignature.verify("other", String.valueOf(now), BODY, sig, 300)).isFalse();
        long old = now - 600;
        assertThat(WebhookSignature.verify(SECRET, String.valueOf(old), BODY,
                WebhookSignature.sign(SECRET, old, BODY), 300)).isFalse();
        assertThat(WebhookSignature.verify(SECRET, null, BODY, sig, 300)).isFalse();
    }
}
