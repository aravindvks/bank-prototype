package bank.pbp.mapping;

import bank.pbp.common.correlation.Correlation;
import bank.pbp.common.error.PbpException;
import bank.pbp.common.events.EventEnvelope;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.Topics;
import bank.pbp.common.ids.Ids;
import bank.pbp.mapping.model.Customer;
import bank.pbp.mapping.model.CustomerStatus;
import bank.pbp.mapping.model.ExternalRef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rules from LLD section 4: a vendor ID belongs to one customer for all time, links are never
 * deleted, parents must exist, and every write is audited and published to mapping-events.
 */
@Service
public class MappingService {

    static final Set<String> SYSTEMS = Set.of("ATFINITY", "ESSENCE", "THREDD");
    static final Set<String> ENTITY_TYPES = Set.of("KYC_CASE", "PARTY", "ACCOUNT", "CARD");
    private static final String PRODUCER = "mapping-service";

    private final MappingRepository repo;
    private final EventPublisher events;
    private final ObjectMapper mapper;

    public MappingService(MappingRepository repo, EventPublisher events, ObjectMapper mapper) {
        this.repo = repo;
        this.events = events;
        this.mapper = mapper;
    }

    @Transactional
    public Customer createCustomer(String customerType, String actor) {
        if (!"INDIVIDUAL".equals(customerType) && !"BUSINESS".equals(customerType)) {
            throw new PbpException("GEN-VALID-001", 400, "Request is not valid",
                    "customerType must be INDIVIDUAL or BUSINESS");
        }
        UUID id = Ids.uuidV7();
        repo.insertCustomer(id, customerType, CustomerStatus.ONBOARDING.name());
        Customer created = repo.findCustomer(id).orElseThrow();
        repo.audit(id, null, "CUSTOMER_CREATED", null, json(created), actor, correlationId());
        publish("customer.created", id, Map.of("customerId", id.toString(), "type", customerType));
        return created;
    }

    public Customer getCustomer(UUID id) {
        return repo.findCustomer(id).orElseThrow(() -> notFound("customer " + id));
    }

    @Transactional
    public Customer changeStatus(UUID id, String target, String reason, Integer expectedVersion, String actor) {
        Customer current = getCustomer(id);
        CustomerStatus from = CustomerStatus.valueOf(current.status());
        CustomerStatus to;
        try {
            to = CustomerStatus.valueOf(target);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new PbpException("GEN-VALID-001", 400, "Request is not valid", "Unknown status " + target);
        }
        if (!from.canMoveTo(to)) {
            throw new PbpException("CUS-STATE-001", 409, "Invalid customer status transition",
                    "Customer cannot move from " + from + " to " + to);
        }
        int version = expectedVersion == null ? current.version() : expectedVersion;
        if (repo.updateStatus(id, to.name(), version) == 0) {
            throw new PbpException("CUS-STATE-001", 409, "Invalid customer status transition",
                    "Customer was changed by someone else; reload and retry");
        }
        Customer updated = getCustomer(id);
        repo.audit(id, null, "STATUS_CHANGED", json(current), json(updated), actor, correlationId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerId", id.toString());
        data.put("from", from.name());
        data.put("to", to.name());
        data.put("reason", reason);
        data.put("version", updated.version());
        publish("customer.status_changed", id, data);
        return updated;
    }

    /** Idempotent on (system, entityType, externalId): same customer returns the existing link. */
    @Transactional
    public LinkResult linkRef(UUID customerId, String system, String entityType, String externalId,
                              UUID parentRefId, String status, Map<String, Object> metadata, String actor) {
        getCustomer(customerId);
        if (!SYSTEMS.contains(system) || !ENTITY_TYPES.contains(entityType)) {
            throw new PbpException("GEN-VALID-001", 400, "Request is not valid",
                    "system must be one of " + SYSTEMS + " and entityType one of " + ENTITY_TYPES);
        }
        Optional<ExternalRef> existing = repo.findRef(system, entityType, externalId);
        if (existing.isPresent()) {
            if (existing.get().customerId().equals(customerId)) {
                return new LinkResult(existing.get(), false);
            }
            throw new PbpException("MAP-LINK-001", 409, "Vendor ID already linked to another customer",
                    system + " " + entityType + " " + externalId + " belongs to another customer");
        }
        checkParent(customerId, entityType, parentRefId);

        ExternalRef ref = new ExternalRef(Ids.uuidV7(), customerId, system, entityType, externalId, parentRefId,
                status == null ? "ACTIVE" : status, null, null, null, correlationId(),
                metadata == null ? null : json(metadata));
        repo.insertRef(ref);
        ExternalRef saved = repo.findRef(ref.refId()).orElseThrow();
        repo.audit(customerId, saved.refId(), "REF_LINKED", null, json(saved), actor, correlationId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerId", customerId.toString());
        data.put("refId", saved.refId().toString());
        data.put("system", system);
        data.put("entityType", entityType);
        data.put("externalId", externalId);
        data.put("parentRef", parentRefId == null ? null : parentRefId.toString());
        publish("ref.linked", customerId, data);
        return new LinkResult(saved, true);
    }

    @Transactional
    public Customer updateKyc(UUID id, String kycStatus, java.time.LocalDate validUntil, String riskRating,
                              String actor) {
        Customer before = getCustomer(id);
        repo.updateKyc(id, kycStatus, validUntil, riskRating);
        Customer after = getCustomer(id);
        repo.audit(id, null, "KYC_UPDATED", json(before), json(after), actor, correlationId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerId", id.toString());
        data.put("kycStatus", kycStatus);
        data.put("validUntil", validUntil == null ? null : validUntil.toString());
        data.put("riskRating", riskRating);
        publish("customer.kyc_updated", id, data);
        return after;
    }

    public List<Customer> customersByStatus(String status) {
        return repo.customersByStatus(status);
    }

    /** Card or account status change and/or metadata merge (card controls, LLD section 4 PATCH /refs). */
    @Transactional
    public ExternalRef updateRef(UUID refId, String status, Map<String, Object> metadata, String actor) {
        ExternalRef before = repo.findRef(refId).orElseThrow(() -> notFound("link " + refId));
        repo.updateRef(refId, status, metadata == null ? null : json(metadata));
        ExternalRef after = repo.findRef(refId).orElseThrow();
        repo.audit(after.customerId(), refId, "REF_UPDATED", json(before), json(after), actor, correlationId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerId", after.customerId().toString());
        data.put("refId", refId.toString());
        data.put("from", before.status());
        data.put("to", after.status());
        data.put("metadata", metadata);
        publish("ref.status_changed", after.customerId(), data);
        return after;
    }

    public Map<String, Object> resolve(String system, String entityType, String externalId) {
        ExternalRef ref = repo.findRef(system, entityType, externalId)
                .orElseThrow(() -> notFound(system + " " + entityType + " " + externalId));
        Customer customer = getCustomer(ref.customerId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("customer", customer);
        out.put("ref", ref);
        out.put("parents", parents(ref));
        return out;
    }

    public Map<String, Object> graph(UUID customerId) {
        Customer customer = getCustomer(customerId);
        List<ExternalRef> refs = repo.refsForCustomer(customerId);
        return Map.of("customer", customer, "refs", refs);
    }

    private List<ExternalRef> parents(ExternalRef ref) {
        java.util.ArrayList<ExternalRef> chain = new java.util.ArrayList<>();
        UUID next = ref.parentRefId();
        while (next != null && chain.size() < 5) {
            ExternalRef parent = repo.findRef(next).orElse(null);
            if (parent == null) {
                break;
            }
            chain.add(parent);
            next = parent.parentRefId();
        }
        return chain;
    }

    /** A card needs an ACTIVE account parent; an account needs a party parent. */
    private void checkParent(UUID customerId, String entityType, UUID parentRefId) {
        String requiredParent = switch (entityType) {
            case "CARD" -> "ACCOUNT";
            case "ACCOUNT" -> "PARTY";
            default -> null;
        };
        if (requiredParent == null) {
            return;
        }
        ExternalRef parent = parentRefId == null ? null : repo.findRef(parentRefId).orElse(null);
        boolean ok = parent != null
                && parent.customerId().equals(customerId)
                && parent.entityType().equals(requiredParent)
                && (!"ACCOUNT".equals(requiredParent) || "ACTIVE".equals(parent.status()));
        if (!ok) {
            throw new PbpException("MAP-LINK-002", 422, "Parent link missing",
                    entityType + " needs " + ("ACCOUNT".equals(requiredParent) ? "an ACTIVE " : "a ")
                            + requiredParent + " parent of the same customer");
        }
    }

    private void publish(String type, UUID customerId, Map<String, Object> data) {
        events.publish(Topics.MAPPING_EVENTS,
                EventEnvelope.of(type, PRODUCER, correlationId(), null, customerId.toString(), data));
    }

    private static String correlationId() {
        return Correlation.current().orElse(null);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PbpException notFound(String what) {
        return new PbpException("GEN-NOTFOUND-001", 404, "Not found", "No " + what);
    }

    public record LinkResult(ExternalRef ref, boolean created) {
    }
}
