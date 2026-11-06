package bank.pbp.ehi;

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

    /** Logging and publishing run here, after the response is sent, off the EHI response path. */
    @Bean(destroyMethod = "shutdown")
    ExecutorService afterResponseExecutor() {
        return Executors.newFixedThreadPool(4);
    }
}
