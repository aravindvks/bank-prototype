package bank.pbp.mapping.model;

import java.util.Map;
import java.util.Set;

/** Allowed customer status transitions (LLD section 4). */
public enum CustomerStatus {
    ONBOARDING, ACTIVE, FROZEN, REJECTED, CLOSED;

    private static final Map<CustomerStatus, Set<CustomerStatus>> ALLOWED = Map.of(
            ONBOARDING, Set.of(ACTIVE, REJECTED),
            ACTIVE, Set.of(FROZEN, CLOSED),
            FROZEN, Set.of(ACTIVE, CLOSED),
            REJECTED, Set.of(),
            CLOSED, Set.of());

    public boolean canMoveTo(CustomerStatus target) {
        return ALLOWED.get(this).contains(target);
    }
}
