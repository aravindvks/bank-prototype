package bank.pbp.common.events;

import bank.pbp.common.correlation.Correlation;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.listener.RecordInterceptor;

/** Restores the correlation ID from the Kafka header before each listener call, and clears it after. */
public class CorrelationRecordInterceptor implements RecordInterceptor<Object, Object> {

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        Header header = record.headers().lastHeader(Correlation.KAFKA_HEADER);
        Correlation.set(header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8));
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        Correlation.clear();
    }
}
