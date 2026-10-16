package limitium.art.camunda.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootApplication
@EnableConfigurationProperties
public class CamundaEvalApplication {

    public static void main(String[] args) {
        SpringApplication.run(CamundaEvalApplication.class, args);
    }

    @Bean
    public ExecutorService executorService() {
        // Test runs are CPU work: at most one per core at a time, the rest wait their turn, so a burst
        // of runs cannot exhaust the server's threads.
        int workers = Math.max(2, Runtime.getRuntime().availableProcessors());
        return new java.util.concurrent.ThreadPoolExecutor(workers, workers, 60, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(500), new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }
}
