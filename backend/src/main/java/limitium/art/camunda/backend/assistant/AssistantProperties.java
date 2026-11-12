package limitium.art.camunda.backend.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The assistant's model: any server speaking the OpenAI chat-completions API (vLLM, llama.cpp,
 * Ollama, LM Studio…), Qwen by intent. Without a URL there is no assistant, and the interface shows
 * none rather than a button that cannot answer.
 */
@ConfigurationProperties(prefix = "dmn.assistant")
@Component
public class AssistantProperties {

    /** The server, e.g. http://qwen:8000/v1 (…/chat/completions is added when missing). Blank: off. */
    private String url = "";
    /** The model name the server knows it by, e.g. Qwen/Qwen2.5-7B-Instruct or qwen2.5:7b. */
    private String model = "qwen2.5:7b";
    /** Sent as a bearer token when the server wants one. */
    private String apiKey = "";
    /** One answer may take a while on a local model; a repair round makes two. */
    private int timeoutSeconds = 120;
    private int maxTokens = 900;
    /** Low: the assistant should say the same true thing twice, not something new each time. */
    private double temperature = 0.2;
    /** Earlier turns sent back with a question; the oldest are dropped first. */
    private int historyTurns = 8;
    /**
     * A folder whose files replace the built-in instructions of the same name (system.md,
     * actions.md, pages/releases.md…), read on every question: the instructions can be tuned
     * without a rebuild. Blank: the built-in ones.
     */
    private String instructionsDir = "";

    public boolean isEnabled() {
        return url != null && !url.isBlank();
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public String getInstructionsDir() {
        return instructionsDir;
    }

    public void setInstructionsDir(String instructionsDir) {
        this.instructionsDir = instructionsDir;
    }

    public int getHistoryTurns() {
        return historyTurns;
    }

    public void setHistoryTurns(int historyTurns) {
        this.historyTurns = historyTurns;
    }
}
