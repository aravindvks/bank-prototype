package bank.pbp.orchestrator.temporal;

import bank.pbp.orchestrator.onboarding.OnboardingActivitiesImpl;
import bank.pbp.orchestrator.onboarding.OnboardingWorkflowImpl;
import bank.pbp.orchestrator.payments.PaymentActivitiesImpl;
import bank.pbp.orchestrator.payments.PaymentWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Temporal client and workers. Namespace pbp-&lt;env&gt;; one worker per task queue. */
@Configuration
public class TemporalConfig {

    @Bean
    WorkflowServiceStubs workflowServiceStubs(@Value("${temporal.target}") String target) {
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
    }

    @Bean
    WorkflowClient workflowClient(WorkflowServiceStubs stubs, @Value("${temporal.namespace}") String namespace) {
        return WorkflowClient.newInstance(stubs, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
    }

    @Bean
    WorkerFactory workerFactory(WorkflowClient client) {
        return WorkerFactory.newInstance(client);
    }

    @Bean
    TemporalWorkers temporalWorkers(WorkerFactory factory, PaymentActivitiesImpl paymentActivities,
                                    OnboardingActivitiesImpl onboardingActivities) {
        return new TemporalWorkers(factory, paymentActivities, onboardingActivities);
    }

    /** Starts polling after the context is ready and stops cleanly on shutdown. */
    static final class TemporalWorkers implements SmartLifecycle {

        private static final Logger log = LoggerFactory.getLogger(TemporalWorkers.class);

        private final WorkerFactory factory;
        private final PaymentActivitiesImpl paymentActivities;
        private final OnboardingActivitiesImpl onboardingActivities;
        private volatile boolean running;

        TemporalWorkers(WorkerFactory factory, PaymentActivitiesImpl paymentActivities,
                        OnboardingActivitiesImpl onboardingActivities) {
            this.factory = factory;
            this.paymentActivities = paymentActivities;
            this.onboardingActivities = onboardingActivities;
        }

        @Override
        public void start() {
            Worker payments = factory.newWorker(TaskQueues.PAYMENTS);
            payments.registerWorkflowImplementationTypes(PaymentWorkflowImpl.class);
            payments.registerActivitiesImplementations(paymentActivities);
            Worker onboarding = factory.newWorker(TaskQueues.ONBOARDING);
            onboarding.registerWorkflowImplementationTypes(OnboardingWorkflowImpl.class);
            onboarding.registerActivitiesImplementations(onboardingActivities);
            factory.start();
            running = true;
            log.info("temporal.workers.started queues=[{}, {}]", TaskQueues.PAYMENTS, TaskQueues.ONBOARDING);
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
