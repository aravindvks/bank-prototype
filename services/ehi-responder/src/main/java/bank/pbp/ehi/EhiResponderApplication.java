package bank.pbp.ehi;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class EhiResponderApplication {

    public static void main(String[] args) {
        SpringApplication.run(EhiResponderApplication.class, args);
    }

    /**
     * Logging and publishing run here, after the response is sent, off the EHI response path. Wrapped so
     * the request's trace carries over, and the card event joins the authorisation's trace (LLD section 15).
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService afterResponseExecutor() {
        ContextSnapshotFactory snapshots = ContextSnapshotFactory.builder().build();
        return ContextExecutorService.wrap(Executors.newFixedThreadPool(4), snapshots::captureAll);
    }
}
