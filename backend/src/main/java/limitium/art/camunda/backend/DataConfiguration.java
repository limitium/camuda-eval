package limitium.art.camunda.backend;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
@ConfigurationProperties(prefix = "dmn")
public class DataConfiguration {
    
    private String dataRoot = "../data";
    private int port = 7070;
    
    public String getDataRoot() {
        return dataRoot;
    }
    
    public void setDataRoot(String dataRoot) {
        this.dataRoot = dataRoot;
    }
    
    public int getPort() {
        return port;
    }
    
    public void setPort(int port) {
        this.port = port;
    }
    
    @Bean
    public Path dataRootPath() {
        return Paths.get(dataRoot);
    }
    
    @Bean
    public Path dmnDir() {
        return dataRootPath().resolve("dmn");
    }
    
    @Bean
    public Path yamlDir() {
        return dataRootPath().resolve("yaml");
    }
    
    @Bean
    public Path runsDir() {
        return dataRootPath().resolve("runs");
    }
}
