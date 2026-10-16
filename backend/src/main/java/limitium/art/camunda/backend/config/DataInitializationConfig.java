package limitium.art.camunda.backend.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class DataInitializationConfig implements CommandLineRunner {

    @Autowired
    private Path dmnDir;
    
    @Autowired
    private Path yamlDir;
    
    @Autowired
    private Path runsDir;

    @Override
    public void run(String... args) throws Exception {
        // Create directories if they don't exist
        Files.createDirectories(dmnDir);
        Files.createDirectories(yamlDir);
        Files.createDirectories(runsDir);
    }
}
