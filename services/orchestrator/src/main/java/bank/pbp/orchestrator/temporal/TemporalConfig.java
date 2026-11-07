package bank.pbp.orchestrator.temporal;

import bank.pbp.orchestrator.cards.CardActivitiesImpl;
import bank.pbp.orchestrator.cards.CardControlsWorkflow;
import bank.pbp.orchestrator.compliance.ComplianceActivitiesImpl;
import bank.pbp.orchestrator.compliance.ComplianceWorkflow;
import bank.pbp.orchestrator.creditcards.CreditCardActivitiesImpl;
import bank.pbp.orchestrator.creditcards.CreditCardWorkflows;
import bank.pbp.orchestrator.directdebits.DirectDebitActivitiesImpl;
import bank.pbp.orchestrator.directdebits.DirectDebitWorkflows;
import bank.pbp.orchestrator.onboarding.OnboardingActivitiesImpl;
import bank.pbp.orchestrator.onboarding.OnboardingWorkflowImpl;
import bank.pbp.orchestrator.payments.InboundActivitiesImpl;
import bank.pbp.orchestrator.payments.InboundPaymentWorkflow;
import bank.pbp.orchestrator.payments.PaymentActivitiesImpl;
import bank.pbp.orchestrator.payments.PaymentWorkflowImpl;
import bank.pbp.orchestrator.schedules.ScheduleActivitiesImpl;
import bank.pbp.orchestrator.schedules.ScheduleWorkflow;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.opentracingshim.OpenTracingShim;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.reporter.MicrometerClientStatsReporter;
import io.temporal.opentracing.OpenTracingClientInterceptor;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.opentracing.OpenTracingWorkerInterceptor;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Temporal client and workers. Namespace pbp-&lt;env&gt;; one worker per task queue. */
@Configuration
public class TemporalConfig {

    /**
     * Temporal SDK metrics (task latency, failures, polls) on the application's Micrometer registry, so
     * Prometheus scrapes them with everything else (LLD section 15).
     */
    @Bean
    WorkflowServiceStubs workflowServiceStubs(@Value("${temporal.target}") String target, MeterRegistry registry) {
        Scope metrics = new RootScopeBuilder()
                .reporter(new MicrometerClientStatsReporter(registry))
                .reportEvery(com.uber.m3.util.Duration.ofSeconds(10));
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(target).setMetricsScope(metrics).build());
    }

    /**
     * OpenTracing interceptors over an OpenTelemetry shim: the Kafka listener's span carries into the
     * workflow it starts, and each activity's HTTP calls hang off the activity span.
     */
    @Bean
    OpenTracingOptions temporalTracing(ObjectProvider<OpenTelemetry> openTelemetry) {
        OpenTelemetry otel = openTelemetry.getIfAvailable(OpenTelemetry::noop);
        return OpenTracingOptions.newBuilder().setTracer(OpenTracingShim.createTracerShim(otel)).build();
    }

    @Bean
    WorkflowClient workflowClient(WorkflowServiceStubs stubs, @Value("${temporal.namespace}") String namespace,
                                  OpenTracingOptions tracing) {
        return WorkflowClient.newInstance(stubs, WorkflowClientOptions.newBuilder().setNamespace(namespace)
                .setInterceptors(new OpenTracingClientInterceptor(tracing)).build());
    }

    @Bean
    WorkerFactory workerFactory(WorkflowClient client, OpenTracingOptions tracing) {
        return WorkerFactory.newInstance(client, WorkerFactoryOptions.newBuilder()
                .setWorkerInterceptors(new OpenTracingWorkerInterceptor(tracing)).build());
    }

    @Bean
    TemporalWorkers temporalWorkers(WorkerFactory factory, PaymentActivitiesImpl paymentActivities,
                                    OnboardingActivitiesImpl onboardingActivities,
                                    ComplianceActivitiesImpl complianceActivities,
                                    InboundActivitiesImpl inboundActivities,
                                    CardActivitiesImpl cardActivities,
                                    ScheduleActivitiesImpl scheduleActivities,
                                    DirectDebitActivitiesImpl directDebitActivities,
                                    CreditCardActivitiesImpl creditCardActivities) {
        return new TemporalWorkers(factory, paymentActivities, onboardingActivities, complianceActivities,
                inboundActivities, cardActivities, scheduleActivities, directDebitActivities, creditCardActivities);
    }

    /** Starts polling after the context is ready and stops cleanly on shutdown. */
    static final class TemporalWorkers implements SmartLifecycle {

        private static final Logger log = LoggerFactory.getLogger(TemporalWorkers.class);

        private final WorkerFactory factory;
        private final PaymentActivitiesImpl paymentActivities;
        private final OnboardingActivitiesImpl onboardingActivities;
        private final ComplianceActivitiesImpl complianceActivities;
        private final InboundActivitiesImpl inboundActivities;
        private final CardActivitiesImpl cardActivities;
        private final ScheduleActivitiesImpl scheduleActivities;
        private final DirectDebitActivitiesImpl directDebitActivities;
        private final CreditCardActivitiesImpl creditCardActivities;
        private volatile boolean running;

        TemporalWorkers(WorkerFactory factory, PaymentActivitiesImpl paymentActivities,
                        OnboardingActivitiesImpl onboardingActivities, ComplianceActivitiesImpl complianceActivities,
                        InboundActivitiesImpl inboundActivities, CardActivitiesImpl cardActivities,
                        ScheduleActivitiesImpl scheduleActivities, DirectDebitActivitiesImpl directDebitActivities,
                        CreditCardActivitiesImpl creditCardActivities) {
            this.factory = factory;
            this.paymentActivities = paymentActivities;
            this.onboardingActivities = onboardingActivities;
            this.complianceActivities = complianceActivities;
            this.inboundActivities = inboundActivities;
            this.cardActivities = cardActivities;
            this.scheduleActivities = scheduleActivities;
            this.directDebitActivities = directDebitActivities;
            this.creditCardActivities = creditCardActivities;
        }

        @Override
        public void start() {
            Worker payments = factory.newWorker(TaskQueues.PAYMENTS);
            payments.registerWorkflowImplementationTypes(PaymentWorkflowImpl.class, InboundPaymentWorkflow.Impl.class,
                    ScheduleWorkflow.Impl.class, DirectDebitWorkflows.MandateImpl.class,
                    DirectDebitWorkflows.CollectionImpl.class, DirectDebitWorkflows.ClaimImpl.class);
            payments.registerActivitiesImplementations(paymentActivities, inboundActivities, scheduleActivities,
                    directDebitActivities);
            Worker onboarding = factory.newWorker(TaskQueues.ONBOARDING);
            onboarding.registerWorkflowImplementationTypes(OnboardingWorkflowImpl.class);
            onboarding.registerActivitiesImplementations(onboardingActivities);
            Worker compliance = factory.newWorker(TaskQueues.COMPLIANCE);
            compliance.registerWorkflowImplementationTypes(ComplianceWorkflow.Impl.class);
            compliance.registerActivitiesImplementations(complianceActivities);
            Worker cards = factory.newWorker(TaskQueues.CARDS);
            cards.registerWorkflowImplementationTypes(CardControlsWorkflow.Impl.class,
                    CreditCardWorkflows.ApplicationImpl.class, CreditCardWorkflows.RepaymentImpl.class,
                    CreditCardWorkflows.StatementImpl.class, CreditCardWorkflows.WithdrawalImpl.class);
            cards.registerActivitiesImplementations(cardActivities, creditCardActivities);
            factory.start();
            running = true;
            log.info("temporal.workers.started queues=[{}, {}, {}]", TaskQueues.PAYMENTS, TaskQueues.ONBOARDING,
                    TaskQueues.COMPLIANCE);
        }

        @Override
        public void stop() {
            factory.shutdown();
            factory.awaitTermination(10, TimeUnit.SECONDS);
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }
}
