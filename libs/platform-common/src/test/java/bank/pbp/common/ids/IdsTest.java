package bank.pbp.common.ids;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void uuidV7HasVersionSevenAndRfcVariant() {
        UUID id = Ids.uuidV7();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void uuidV7IsTimeOrdered() throws InterruptedException {
        UUID first = Ids.uuidV7();
        Thread.sleep(2);
        UUID second = Ids.uuidV7();
        assertThat(first.toString()).isLessThan(second.toString());
    }

    @Test
    void correlationIdMatchesLldFormat() {
        String id = Ids.newCorrelationId(JourneyType.PAY);
        assertThat(id).startsWith("COR-PAY-").hasSize(20);
        assertThat(Ids.isCorrelationId(id)).isTrue();
    }

    @Test
    void rejectsMalformedCorrelationIds() {
        assertThat(Ids.isCorrelationId(null)).isFalse();
        assertThat(Ids.isCorrelationId("COR-XYZ-7K2M9QXA4B1D")).isFalse();
        assertThat(Ids.isCorrelationId("COR-PAY-7k2m9qxa4b1d")).isFalse();
        assertThat(Ids.isCorrelationId("COR-PAY-7K2M9QXA4B1D")).isTrue();
    }
}
