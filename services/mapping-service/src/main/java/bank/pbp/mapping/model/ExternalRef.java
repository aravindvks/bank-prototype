package bank.pbp.mapping.model;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ExternalRef(
        UUID refId,
        UUID customerId,
        String system,
        String entityType,
        String externalId,
        UUID parentRefId,
        String status,
        OffsetDateTime validFrom,
        OffsetDateTime validTo,
        UUID replacedBy,
        String createdCorrelationId,
        @JsonRawValue String metadata) {
}
