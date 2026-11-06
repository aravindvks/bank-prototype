package bank.pbp.common.ids;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Identifier formats from LLD section 1.
 * <ul>
 *   <li>Customer, payment and event IDs: UUID v7 (time ordered).</li>
 *   <li>Correlation ID: {@code COR-<TYPE>-<12 base32 chars>}, e.g. COR-PAY-7K2M9QXA4B1D.</li>
 * </ul>
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final Pattern CORRELATION = Pattern.compile("^COR-(ONB|PAY|CRD|TXN|CUS|REC)-[A-Z2-7]{12}$");

    private Ids() {
    }

    /** RFC 9562 UUID version 7: 48-bit Unix milliseconds, version, random bits, variant. */
    public static UUID uuidV7() {
        long millis = System.currentTimeMillis();
        byte[] r = new byte[10];
        RANDOM.nextBytes(r);
        long randA = ((r[0] & 0x0FL) << 8) | (r[1] & 0xFFL);
        long msb = (millis << 16) | (0x7L << 12) | randA;
        long lsb = 0;
        for (int i = 2; i < 10; i++) {
            lsb = (lsb << 8) | (r[i] & 0xFFL);
        }
        lsb = (lsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    public static String newCorrelationId(JourneyType type) {
        StringBuilder sb = new StringBuilder(21).append("COR-").append(type.name()).append('-');
        for (int i = 0; i < 12; i++) {
            sb.append(BASE32[RANDOM.nextInt(32)]);
        }
        return sb.toString();
    }

    public static boolean isCorrelationId(String value) {
        return value != null && CORRELATION.matcher(value).matches();
    }
}
