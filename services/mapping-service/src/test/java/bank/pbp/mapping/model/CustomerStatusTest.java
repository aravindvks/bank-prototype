package bank.pbp.mapping.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CustomerStatusTest {

    @Test
    void followsLldTransitions() {
        assertThat(CustomerStatus.ONBOARDING.canMoveTo(CustomerStatus.ACTIVE)).isTrue();
        assertThat(CustomerStatus.ONBOARDING.canMoveTo(CustomerStatus.REJECTED)).isTrue();
        assertThat(CustomerStatus.ACTIVE.canMoveTo(CustomerStatus.FROZEN)).isTrue();
        assertThat(CustomerStatus.FROZEN.canMoveTo(CustomerStatus.ACTIVE)).isTrue();
        assertThat(CustomerStatus.FROZEN.canMoveTo(CustomerStatus.CLOSED)).isTrue();
    }

    @Test
    void refusesInvalidTransitions() {
        assertThat(CustomerStatus.ONBOARDING.canMoveTo(CustomerStatus.FROZEN)).isFalse();
        assertThat(CustomerStatus.REJECTED.canMoveTo(CustomerStatus.ACTIVE)).isFalse();
        assertThat(CustomerStatus.CLOSED.canMoveTo(CustomerStatus.ACTIVE)).isFalse();
    }
}
