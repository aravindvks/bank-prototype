package bank.pbp.bff.staff;

import bank.pbp.common.error.VendorErrors;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Staff portal entry point. Until staff login (Identity Domains, compliance role) is added, the
 * reviewer is taken from X-Staff-User and recorded as the actor on every change.
 */
@RestController
@RequestMapping("/v1/staff")
public class StaffController {

    private final RestClient orchestrator;

    public StaffController(RestClient.Builder builder, @Value("${pbp.services.orchestrator-url}") String url) {
        this.orchestrator = builder.clone().baseUrl(url).build();
    }

    @GetMapping("/queue")
    public JsonNode queue(@RequestHeader("X-Staff-User") String staff) {
        return get("/v1/staff/queue");
    }

    @GetMapping("/sagas")
    public JsonNode sagas(@RequestHeader("X-Staff-User") String staff) {
        return get("/v1/staff/sagas");
    }

    @GetMapping("/journeys/{correlationId}")
    public JsonNode journey(@PathVariable String correlationId, @RequestHeader("X-Staff-User") String staff) {
        return get("/v1/staff/journeys/" + correlationId);
    }

    @PostMapping("/reviews/{sagaId}")
    public JsonNode review(@PathVariable UUID sagaId, @Valid @RequestBody Decision body,
                           @RequestHeader("X-Staff-User") String staff) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("decision", body.decision());
        req.put("reviewer", staff);
        req.put("note", body.note());
        return VendorErrors.call("orchestrator", "staff.review", () -> orchestrator.post()
                .uri("/v1/staff/reviews/{id}", sagaId).body(req).retrieve().body(JsonNode.class));
    }

    private JsonNode get(String path) {
        return VendorErrors.call("orchestrator", "staff.get", () -> orchestrator.get().uri(path)
                .retrieve().body(JsonNode.class));
    }

    public record Decision(@NotBlank @Pattern(regexp = "CLEAR|CONFIRM") String decision, String note) {
    }
}
