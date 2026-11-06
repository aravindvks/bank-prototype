package bank.pbp.simulator;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fault injection driven by the X-Sim-Scenario request header, so tests and the demo
 * toolbar can force vendor behaviour without changing simulator state.
 * <ul>
 *   <li>{@code slow}: answer after 2.5 s (beyond the EHI budget, inside the 10 s standard timeout)</li>
 *   <li>{@code timeout}: answer after 12 s (beyond every client timeout)</li>
 *   <li>{@code error}: 503 Service Unavailable</li>
 * </ul>
 */
public final class Scenario {

    public static final String HEADER = "X-Sim-Scenario";

    private Scenario() {
    }

    public static void apply(String scenario) {
        if (scenario == null || scenario.isBlank()) {
            return;
        }
        switch (scenario) {
            case "slow" -> sleep(2_500);
            case "timeout" -> sleep(12_000);
            case "error" -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Simulated vendor outage");
            default -> {
                // Unknown scenarios behave normally.
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
