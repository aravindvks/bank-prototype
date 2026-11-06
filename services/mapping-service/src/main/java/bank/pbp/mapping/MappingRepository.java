package bank.pbp.mapping;

import bank.pbp.mapping.model.Customer;
import bank.pbp.mapping.model.ExternalRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MappingRepository {

    private static final String REF_COLUMNS = """
            ref_id, customer_id, system, entity_type, external_id, parent_ref_id, status,
            valid_from, valid_to, replaced_by, created_correlation_id, metadata::text AS metadata""";

    private final JdbcClient jdbc;

    public MappingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertCustomer(UUID id, String type, String status) {
        jdbc.sql("INSERT INTO customer (customer_id, customer_type, status) VALUES (:id, :type, :status)")
                .param("id", id).param("type", type).param("status", status)
                .update();
    }

    public Optional<Customer> findCustomer(UUID id) {
        return jdbc.sql("""
                        SELECT customer_id, customer_type, status, kyc_status, kyc_valid_until, risk_rating,
                               created_at, version
                        FROM customer WHERE customer_id = :id""")
                .param("id", id)
                .query(Customer.class)
                .optional();
    }

    /** Optimistic lock: updates only when the version still matches. Returns rows changed. */
    public int updateStatus(UUID id, String status, int expectedVersion) {
        return jdbc.sql("""
                        UPDATE customer SET status = :status, version = version + 1
                        WHERE customer_id = :id AND version = :version""")
                .param("status", status).param("id", id).param("version", expectedVersion)
                .update();
    }

    public void insertRef(ExternalRef ref) {
        jdbc.sql("""
                        INSERT INTO external_ref (ref_id, customer_id, system, entity_type, external_id,
                                                  parent_ref_id, status, created_correlation_id, metadata)
                        VALUES (:refId, :customerId, :system, :entityType, :externalId, :parentRefId, :status,
                                :correlationId, CAST(:metadata AS jsonb))""")
                .param("refId", ref.refId())
                .param("customerId", ref.customerId())
                .param("system", ref.system())
                .param("entityType", ref.entityType())
                .param("externalId", ref.externalId())
                .param("parentRefId", ref.parentRefId())
                .param("status", ref.status())
                .param("correlationId", ref.createdCorrelationId())
                .param("metadata", ref.metadata())
                .update();
    }

    public int updateKyc(UUID id, String kycStatus, java.time.LocalDate validUntil, String riskRating) {
        return jdbc.sql("""
                        UPDATE customer SET kyc_status = :kyc, kyc_valid_until = :until, risk_rating = :risk,
                               version = version + 1
                        WHERE customer_id = :id""")
                .param("kyc", kycStatus).param("until", validUntil).param("risk", riskRating).param("id", id)
                .update();
    }

    public Optional<ExternalRef> findRef(UUID refId) {
        return jdbc.sql("SELECT " + REF_COLUMNS + " FROM external_ref WHERE ref_id = :id")
                .param("id", refId)
                .query(ExternalRef.class)
                .optional();
    }

    public Optional<ExternalRef> findRef(String system, String entityType, String externalId) {
        return jdbc.sql("SELECT " + REF_COLUMNS + """
                         FROM external_ref
                        WHERE system = :system AND entity_type = :type AND external_id = :id""")
                .param("system", system).param("type", entityType).param("id", externalId)
                .query(ExternalRef.class)
                .optional();
    }

    public List<ExternalRef> refsForCustomer(UUID customerId) {
        return jdbc.sql("SELECT " + REF_COLUMNS + " FROM external_ref WHERE customer_id = :id ORDER BY valid_from")
                .param("id", customerId)
                .query(ExternalRef.class)
                .list();
    }

    public void audit(UUID customerId, UUID refId, String action, String beforeJson, String afterJson,
                      String actor, String correlationId) {
        jdbc.sql("""
                        INSERT INTO mapping_audit (customer_id, ref_id, action, before_state, after_state, actor,
                                                   correlation_id)
                        VALUES (:customerId, :refId, :action, CAST(:before AS jsonb), CAST(:after AS jsonb), :actor,
                                :correlationId)""")
                .param("customerId", customerId)
                .param("refId", refId)
                .param("action", action)
                .param("before", beforeJson)
                .param("after", afterJson)
                .param("actor", actor)
                .param("correlationId", correlationId)
                .update();
    }
}
