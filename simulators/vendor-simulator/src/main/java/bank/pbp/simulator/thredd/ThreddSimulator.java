package bank.pbp.simulator.thredd;

import bank.pbp.simulator.Scenario;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thredd stand-in: card issue. Tokens only; no PAN ever leaves the simulator. */
@RestController
@RequestMapping("/thredd/v1")
public class ThreddSimulator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<String, Map<String, Object>> cardsByIdempotencyKey = new ConcurrentHashMap<>();

    @PostMapping("/cards")
    public ResponseEntity<Map<String, Object>> issue(@RequestBody Map<String, Object> request,
                                                     @RequestHeader(value = "IdempotencyKey", required = false) String key,
                                                     @RequestHeader(value = Scenario.HEADER, required = false) String scenario) {
        Scenario.apply(scenario);
        if (key != null && cardsByIdempotencyKey.containsKey(key)) {
            return ResponseEntity.ok(cardsByIdempotencyKey.get(key));
        }
        Map<String, Object> card = Map.of(
                "token", "thr_tok_" + Integer.toHexString(0x100000 + RANDOM.nextInt(0xEFFFFF)),
                "last4", String.format("%04d", RANDOM.nextInt(10_000)),
                "status", "INACTIVE",
                "accountRef", String.valueOf(request.getOrDefault("accountRef", "")));
        if (key != null) {
            cardsByIdempotencyKey.put(key, card);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(card);
    }
}
