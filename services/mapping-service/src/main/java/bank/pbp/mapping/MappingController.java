package bank.pbp.mapping;

import bank.pbp.mapping.MappingService.LinkResult;
import bank.pbp.mapping.model.Customer;
import bank.pbp.mapping.model.ExternalRef;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal API from LLD section 4. Not exposed through the API gateway. */
@RestController
@RequestMapping("/v1")
public class MappingController {

    private final MappingService service;

    public MappingController(MappingService service) {
        this.service = service;
    }

    @PostMapping("/customers")
    public ResponseEntity<Customer> create(@Valid @RequestBody CreateCustomer body,
                                           @RequestHeader(value = "X-Actor", defaultValue = "svc:unknown") String actor) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCustomer(body.customerType(), actor));
    }

    @GetMapping("/customers/{id}")
    public Customer get(@PathVariable UUID id) {
        return service.getCustomer(id);
    }

    @GetMapping("/customers/{id}/graph")
    public Map<String, Object> graph(@PathVariable UUID id) {
        return service.graph(id);
    }

    @PatchMapping("/customers/{id}/status")
    public Customer status(@PathVariable UUID id, @Valid @RequestBody StatusChange body,
                           @RequestHeader(value = "X-Actor", defaultValue = "svc:unknown") String actor) {
        return service.changeStatus(id, body.status(), body.reason(), body.expectedVersion(), actor);
    }

    @PostMapping("/customers/{id}/refs")
    public ResponseEntity<ExternalRef> link(@PathVariable UUID id, @Valid @RequestBody LinkRef body,
                                            @RequestHeader(value = "X-Actor", defaultValue = "svc:unknown") String actor) {
        LinkResult result = service.linkRef(id, body.system(), body.entityType(), body.externalId(),
                body.parentRefId(), body.status(), body.metadata(), actor);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.ref());
    }

    @PatchMapping("/customers/{id}/kyc")
    public Customer kyc(@PathVariable UUID id, @Valid @RequestBody KycUpdate body,
                        @RequestHeader(value = "X-Actor", defaultValue = "svc:unknown") String actor) {
        return service.updateKyc(id, body.kycStatus(), body.validUntil(), body.riskRating(), actor);
    }

    @GetMapping("/customers")
    public java.util.List<Customer> byStatus(@RequestParam String status) {
        return service.customersByStatus(status);
    }

    @PatchMapping("/refs/{refId}")
    public ExternalRef updateRef(@PathVariable UUID refId, @RequestBody RefUpdate body,
                                 @RequestHeader(value = "X-Actor", defaultValue = "svc:unknown") String actor) {
        return service.updateRef(refId, body.status(), body.metadata(), actor);
    }

    @GetMapping("/resolve")
    public Map<String, Object> resolve(@RequestParam String system, @RequestParam String type,
                                       @RequestParam String id) {
        return service.resolve(system, type, id);
    }

    public record CreateCustomer(@NotBlank String customerType) {
    }

    public record StatusChange(@NotBlank String status, String reason, Integer expectedVersion) {
    }

    public record LinkRef(@NotBlank String system, @NotBlank String entityType, @NotBlank String externalId,
                          UUID parentRefId, String status, Map<String, Object> metadata) {
    }

    public record RefUpdate(String status, Map<String, Object> metadata) {
    }

    public record KycUpdate(@NotBlank String kycStatus, java.time.LocalDate validUntil, String riskRating) {
    }
}
