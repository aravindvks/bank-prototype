package bank.pbp.common.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 webhook signatures with a replay window (LLD sections 6 and 12).
 * The signed text is {@code <timestamp>.<raw body>}; headers carry X-Timestamp and X-Signature.
 * Real vendors may use a different scheme; each sandbox connector adapts to it.
 */
public final class WebhookSignature {

    public static final String TIMESTAMP_HEADER = "X-Timestamp";
    public static final String SIGNATURE_HEADER = "X-Signature";

    private WebhookSignature() {
    }

    public static String sign(String secret, long epochSeconds, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((epochSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** True when the signature matches and the timestamp is inside the replay window. */
    public static boolean verify(String secret, String timestampHeader, String body, String signature,
                                 long windowSeconds) {
        if (timestampHeader == null || signature == null || body == null) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(Instant.now().getEpochSecond() - ts) > windowSeconds) {
            return false;
        }
        byte[] expected = sign(secret, ts, body).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8));
    }
}
