package bank.pbp.thredd.gateway;

import bank.pbp.common.error.VendorErrors;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "vendor.thredd.mode", havingValue = "simulator", matchIfMissing = true)
public class SimulatorThreddGateway implements ThreddGateway {

    /** Any 13–19 digit run is treated as a possible PAN and refused before logging or storing. */
    static final Pattern PAN = Pattern.compile("\\b\\d{13,19}\\b");
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final RestClient thredd;

    public SimulatorThreddGateway(RestClient.Builder builder, @Value("${vendor.thredd.base-url}") String baseUrl,
                                  @Value("${vendor.thredd.timeout-ms}") int timeoutMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(1000);
        f.setReadTimeout(timeoutMs);
        this.thredd = builder.clone().baseUrl(baseUrl).requestFactory(f).build();
    }

    @Override
    public Card issueCard(String productId, String accountRef, String embossName, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productId", productId);
        body.put("accountRef", accountRef);
        body.put("embossName", embossName);
        Map<String, Object> card = VendorErrors.call("thredd", "cards.issue", () -> thredd.post()
                .uri("/cards")
                .header("IdempotencyKey", idempotencyKey)
                .body(body)
                .retrieve()
                .body(MAP));
        if (card == null || PAN.matcher(card.toString()).find()) {
            throw new IllegalStateException("Thredd response refused: missing or contains a card-number pattern");
        }
        return new Card(String.valueOf(card.get("token")), String.valueOf(card.get("last4")),
                String.valueOf(card.get("status")));
    }

    @Override
    public void setStatus(String token, String status, String idempotencyKey) {
        VendorErrors.call("thredd", "cards.status", () -> thredd.post()
                .uri("/cards/{token}/status", token)
                .header("IdempotencyKey", idempotencyKey)
                .body(Map.of("status", status))
                .retrieve()
                .toBodilessEntity());
    }
}
