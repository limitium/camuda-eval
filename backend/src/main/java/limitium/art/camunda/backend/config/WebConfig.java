package limitium.art.camunda.backend.config;

import limitium.art.camunda.backend.git.SpaceEditInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SpaceEditInterceptor spaceEdits;
    private final DeployOnlyGuard deployOnly;

    public WebConfig(SpaceEditInterceptor spaceEdits, DeployOnlyGuard deployOnly) {
        this.spaceEdits = spaceEdits;
        this.deployOnly = deployOnly;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Production changes nothing: checked before anything else.
        registry.addInterceptor(deployOnly).addPathPatterns("/api/**");
        // Every read and write of a space's files holds that space shared for the whole request.
        registry.addInterceptor(spaceEdits).addPathPatterns("/api/dmn/**", "/api/yaml/**", "/api/dmn", "/api/yaml");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Same domain deployment: allow all origins in dev; typically unnecessary in prod when same host is used
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}