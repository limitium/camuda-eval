package limitium.art.camunda.backend.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * One chat completion from an OpenAI-compatible server, asked for a JSON object. Nothing else is
 * sent anywhere: the server is the one configured, and what it says back is only text until the
 * controller has checked it.
 */
@Component
public class ChatModel {

    private static final Logger LOG = LoggerFactory.getLogger(ChatModel.class);

    /** A turn of the conversation: system, user or assistant. */
    public static class Message {
        public final String role;
        public final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /** The model could not be asked, or did not answer; the message says which, in words. */
    public static class Failed extends RuntimeException {
        public Failed(String message) {
            super(message);
        }
    }

    private final AssistantProperties props;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    /** Some servers refuse response_format; once one has, it is not sent again. */
    private volatile boolean jsonMode = true;

    public ChatModel(AssistantProperties props) {
        this.props = props;
    }

    public String endpoint() {
        String url = props.getUrl().trim().replaceAll("/+$", "");
        return url.endsWith("/chat/completions") ? url : url + "/chat/completions";
    }

    public String complete(List<Message> messages) {
        try {
            HttpResponse<String> response = send(messages, jsonMode);
            if (response.statusCode() == 400 && jsonMode && response.body().contains("response_format")) {
                LOG.info("assistant: {} refuses response_format; asking without it from now on", endpoint());
                jsonMode = false;
                response = send(messages, false);
            }
            if (response.statusCode() / 100 == 5) {
                // A local server sometimes fails one generation (Ollama in JSON mode does); once more.
                response = send(messages, jsonMode);
            }
            if (response.statusCode() / 100 != 2) {
                throw new Failed("the model server answered " + response.statusCode() + ": " + abbreviate(response.body(), 300));
            }
            JsonNode body = json.readTree(response.body());
            JsonNode content = body.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) {
                throw new Failed("the model server sent no answer");
            }
            JsonNode usage = body.path("usage");
            LOG.info("assistant: {} prompt tokens, {} answer tokens", usage.path("prompt_tokens").asInt(-1), usage.path("completion_tokens").asInt(-1));
            LOG.debug("assistant reply: {}", content.asText());
            return content.asText();
        } catch (Failed e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new Failed("the model took longer than " + props.getTimeoutSeconds() + " s");
        } catch (java.net.ConnectException e) {
            throw new Failed("the model server at " + endpoint() + " cannot be reached");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failed("interrupted");
        } catch (Exception e) {
            throw new Failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private HttpResponse<String> send(List<Message> messages, boolean asJson) throws Exception {
        ObjectNode request = json.createObjectNode();
        request.put("model", props.getModel());
        request.put("temperature", props.getTemperature());
        request.put("max_tokens", props.getMaxTokens());
        request.put("stream", false);
        if (asJson) {
            request.putObject("response_format").put("type", "json_object");
        }
        ArrayNode list = request.putArray("messages");
        for (Message m : messages) {
            list.addObject().put("role", m.role).put("content", m.content);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint()))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request)));
        if (props.getApiKey() != null && !props.getApiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + props.getApiKey().trim());
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String abbreviate(String text, int max) {
        String t = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
