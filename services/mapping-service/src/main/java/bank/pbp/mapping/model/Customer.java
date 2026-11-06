package bank.pbp.mapping.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Customer(
        UUID customerId,
        String customerType,
        String status,
        String kycStatus,
        LocalDate kycValidUntil,
        String riskRating,
        OffsetDateTime createdAt,
        int version) {
}
