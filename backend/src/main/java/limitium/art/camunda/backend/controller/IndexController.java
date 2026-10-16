package limitium.art.camunda.backend.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Controller
public class IndexController {

    @Value("${BASE_URL:}")
    private String baseUrl;

    // Root route and catch-all routes for client-side routing (SPA fallback)
    @GetMapping({"/", "/dmn/**", "/test/**", "/run/**", "/new", "/space/**", "/running", "/running/**", "/deploy", "/tidy", "/commit/**", "/releases", "/applications/**", "/work", "/work/**"})
    public ResponseEntity<String> index() throws IOException {
        return serveIndexHtml();
    }
    
    private ResponseEntity<String> serveIndexHtml() throws IOException {
        Resource resource = new ClassPathResource("static/index.html");
        String html = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        
        // Inject BASE_URL into the HTML
        String basePath = baseUrl.isEmpty() ? "" : "/" + baseUrl.replaceAll("^/+|/+$", "");
        String injectedHtml = html.replace(
            "<head>",
            "<head>\n    <script>window.BASE_URL = '" + basePath + "';</script>"
        );
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        
        return ResponseEntity.ok()
                .headers(headers)
                .body(injectedHtml);
    }
}
