package bank.pbp.bff.staff;

import bank.pbp.bff.security.Caller;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Staff portal entry point. Viewing needs the staff role; decisions and reconciliation runs need
 * compliance (see SecurityConfig). The signed-in reviewer is recorded as the actor on every change.
 */
@RestController
@RequestMapping("/v1/staff")
public class StaffController {

    private final RestClient orchestrator;
    private final RestClient recon;
    private final Caller caller;

    public StaffController(Caller caller, RestClient.Builder builder, @Value("${pbp.services.orchestrator-url}") String url,
                           @Value("${pbp.services.recon-url}") String reconUrl) {
        this.caller = caller;
        this.orchestrator = builder.clone().baseUrl(url).build();
        this.recon = builder.clone().baseUrl(reconUrl).build();
    }

    @GetMapping("/recon/runs")
    public JsonNode reconRuns() {
        return VendorErrors.call("recon", "runs.list", () -> recon.get().uri("/v1/recon/runs").retrieve().body(JsonNode.class));
    }

    @GetMapping("/recon/exceptions")
    public JsonNode reconExceptions() {
        return VendorErrors.call("recon", "exceptions.list", () -> recon.get().uri("/v1/recon/exceptions")
                .retrieve().body(JsonNode.class));
    }

    @PostMapping("/recon/runs")
    public JsonNode reconRun() {
        return VendorErrors.call("recon", "runs.trigger", () -> recon.post().uri("/v1/recon/runs")
                .retrieve().body(JsonNode.class));
    }

    @GetMapping("/queue")
    public JsonNode queue() {
        return get("/v1/staff/queue");
    }

    @GetMapping("/sagas")
    public JsonNode sagas() {
        return get("/v1/staff/sagas");
    }

    @GetMapping("/journeys/{correlationId}")
    public JsonNode journey(@PathVariable String correlationId) {
        return get("/v1/staff/journeys/" + correlationId);
    }

    @PostMapping("/reviews/{sagaId}")
    public JsonNode review(@PathVariable UUID sagaId, @Valid @RequestBody Decision body, HttpServletRequest request) {
        String staff = caller.username(request);
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("decision", body.decision());
        req.put("reviewer", staff);
        req.put("note", body.note());
        return VendorErrors.call("orchestrator", "staff.review", () -> orchestrator.post()
                .uri("/v1/staff/reviews/{id}", sagaId).body(req).retrieve().body(JsonNode.class));
    }

    /** Approve or reject a HELD high-value payment. */
    @PostMapping("/payments/{paymentId}/decision")
    public JsonNode paymentDecision(@PathVariable UUID paymentId, @Valid @RequestBody PaymentDecision body,
                                    HttpServletRequest request) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("decision", body.decision());
        req.put("reviewer", caller.username(request));
        req.put("note", body.note());
        return VendorErrors.call("orchestrator", "staff.payment_decision", () -> orchestrator.post()
                .uri("/v1/staff/payments/{id}/decision", paymentId).body(req).retrieve().body(JsonNode.class));
    }

    /** Links an online banking login to an existing bank customer (e.g. a customer opened in branch). */
    @PostMapping("/logins")
    public Map<String, Object> linkLogin(@Valid @RequestBody LoginLink body, HttpServletRequest request) {
        caller.link(body.username(), body.customerId(), "user:" + caller.username(request));
        return Map.of("username", body.username(), "customerId", body.customerId().toString(), "status", "LINKED");
    }

    public record LoginLink(@NotBlank String username, @jakarta.validation.constraints.NotNull UUID customerId) {
    }

    private JsonNode get(String path) {
        return VendorErrors.call("orchestrator", "staff.get", () -> orchestrator.get().uri(path)
                .retrieve().body(JsonNode.class));
    }

    public record PaymentDecision(@NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision, String note) {
    }

    public record Decision(@NotBlank @Pattern(regexp = "CLEAR|CONFIRM") String decision, String note) {
    }
}
