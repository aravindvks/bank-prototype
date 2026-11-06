package bank.pbp.simulator;

import bank.pbp.simulator.essence.EssenceSimulator;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Demo and test controls. Never deployed outside dev and demo environments. */
@RestController
public class AdminController {

    private final EssenceSimulator essence;

    public AdminController(EssenceSimulator essence) {
        this.essence = essence;
    }

    @PostMapping("/sim/reset")
    public Map<String, String> reset() {
        essence.reset();
        return Map.of("status", "RESET");
    }
}
