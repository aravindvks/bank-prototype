package bank.pbp.common;

import bank.pbp.common.correlation.CorrelationClientInterceptor;
import bank.pbp.common.correlation.CorrelationIdFilter;
import bank.pbp.common.error.ProblemAdvice;
import bank.pbp.common.events.CorrelationRecordInterceptor;
import bank.pbp.common.events.EventPublisher;
import bank.pbp.common.events.OutboxWriter;
import bank.pbp.common.ids.JourneyType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Shared platform wiring every service gets by depending on platform-common:
 * correlation IDs in, out and through Kafka, and the problem+json error envelope.
 */
@AutoConfiguration(afterName = {"org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration"})
public class PlatformAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class WebWiring {

        @Bean
        FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter(
                @Value("${pbp.correlation.default-type:TXN}") JourneyType defaultType) {
            FilterRegistrationBean<CorrelationIdFilter> bean =
                    new FilterRegistrationBean<>(new CorrelationIdFilter(defaultType));
            bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return bean;
        }

        @Bean
        RestClientCustomizer correlationRestClientCustomizer(
                @Value("${spring.application.name:unknown}") String serviceName) {
            return builder -> builder.requestInterceptor(new CorrelationClientInterceptor(serviceName));
        }

        @Bean
        @ConditionalOnMissingBean
        ProblemAdvice problemAdvice() {
            return new ProblemAdvice();
        }
    }

    /** Outbox mode (pbp.events.mode=outbox): events go to the service's outbox table for Debezium. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.jdbc.core.JdbcTemplate")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "pbp.events.mode", havingValue = "outbox")
    static class OutboxWiring {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(type = "org.springframework.jdbc.core.JdbcTemplate")
        OutboxWriter outboxWriter(org.springframework.jdbc.core.JdbcTemplate jdbc) {
            return new OutboxWriter(jdbc);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
    static class KafkaWiring {

        @Bean
        @ConditionalOnMissingBean
        RecordInterceptor<Object, Object> correlationRecordInterceptor() {
            return new CorrelationRecordInterceptor();
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(KafkaTemplate.class)
        EventPublisher eventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper mapper,
                                      org.springframework.beans.factory.ObjectProvider<OutboxWriter> outbox) {
            return new EventPublisher(kafkaTemplate, mapper, outbox.getIfAvailable());
        }
    }
}
