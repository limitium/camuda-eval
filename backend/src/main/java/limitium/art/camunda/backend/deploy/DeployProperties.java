package limitium.art.camunda.backend.deploy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The applications this instance controls: each reads rules: it has a name,
 * the broker and topic it reads them from, and the rule files (DMN) it reads — usually the one of
 * its own, sometimes shared ones too. Each instance gets its list from outside (env files, a
 * k8s config server via {@code spring.config.import}); no two instances control the same broker topic.
 *
 * <pre>
 * dmn.deploy.env=stg
 * dmn.deploy.applications.emea-street.bootstrap-servers=${EMEA_STREET_KAFKA}
 * dmn.deploy.applications.emea-street.topic=dmn-emea-street-stg
 * dmn.deploy.applications.emea-street.files=fees,pricing          # reads the shared pricing rules too
 * dmn.deploy.applications.emea-street.properties[security.protocol]=SASL_SSL
 * dmn.deploy.applications.emea-street.properties[sasl.jaas.config]=...password="${EMEA_STREET_KAFKA_PASSWORD}";
 * # or from the environment:  DMN_DEPLOY_APPLICATIONS_EMEASTREET_BOOTSTRAPSERVERS=kafka-stg:9093
 * </pre>
 *
 * Secrets never sit in a file: they come from the environment through {@code ${...}} placeholders,
 * and nothing in {@link Application#properties} is ever returned by the API or written to a log.
 */
@Component
@ConfigurationProperties(prefix = "dmn.deploy")
public class DeployProperties {

    private String env = "dev";
    private boolean allowDraftPublish = false;
    /** A deploy refuses to start while any domain's tests fail at the version being deployed. */
    private boolean requireTests = true;
    private Map<String, Application> applications = new LinkedHashMap<>();

    public static class Application {
        /** The rule files (DMN base names) this application reads; defaults to the application's name. */
        private java.util.List<String> files = new java.util.ArrayList<>();

        public java.util.List<String> getFiles() {
            return files;
        }

        public void setFiles(java.util.List<String> files) {
            this.files = files;
        }

        /** kafka, or file: a folder of envelopes, for a laptop or a test with no broker. */
        private String type = "kafka";
        private String bootstrapServers = "";
        /** Defaults to dmn-&lt;application&gt;-&lt;env&gt;. */
        private String topic = "";
        /** For type=file: the folder standing in for the topic. */
        private String dir = "";
        /** Passed to the Kafka clients as they are: security, TLS, timeouts. May hold secrets. */
        private Map<String, String> properties = new LinkedHashMap<>();
        /** How long reading the topic back may take before the server is reported unreachable. */
        private int timeoutSeconds = 15;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }

        public Map<String, String> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, String> properties) {
            this.properties = properties;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public boolean isAllowDraftPublish() {
        return allowDraftPublish;
    }

    public void setAllowDraftPublish(boolean allowDraftPublish) {
        this.allowDraftPublish = allowDraftPublish;
    }

    public boolean isRequireTests() {
        return requireTests;
    }

    public void setRequireTests(boolean requireTests) {
        this.requireTests = requireTests;
    }

    public Map<String, Application> getApplications() {
        return applications;
    }

    public void setApplications(Map<String, Application> applications) {
        this.applications = applications;
    }
}
