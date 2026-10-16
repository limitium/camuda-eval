package limitium.art.camunda.backend.deploy;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Kafka target against a real broker: one record per domain, tombstones, read back, topic checks. */
class KafkaTargetIT {

    private static EmbeddedKafkaKraftBroker broker;

    @BeforeAll
    static void start() throws Exception {
        broker = new EmbeddedKafkaKraftBroker(1, 1);
        broker.afterPropertiesSet();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()))) {
            admin.createTopics(List.of(
                    new NewTopic("dmn-rules-it", 1, (short) 1).configs(Map.of("cleanup.policy", "compact")),
                    new NewTopic("dmn-rules-wrong", 3, (short) 1))).all().get();
        }
    }

    @AfterAll
    static void stop() {
        if (broker != null) {
            broker.destroy();
        }
    }

    private static Envelope envelope(String name, String commit) {
        Envelope e = new Envelope();
        e.name = name;
        e.dmnXml = "<definitions/>";
        e.commit = commit;
        e.release = "v1.0.0";
        return e;
    }

    @Test
    @DisplayName("publishes one record per domain; reads back the newest; a tombstone stops a domain")
    void publishAndReadBack() throws Exception {
        KafkaTarget target = new KafkaTarget("it", broker.getBrokersAsString(), "dmn-rules-it", Map.of(), 20);
        target.publish(List.of(envelope("pricing", "a1"), envelope("fees", "a1")), List.of());
        target.publish(List.of(envelope("pricing", "b2")), List.of("fees"));
        Map<String, Envelope> held = target.readBack();
        assertEquals(List.of("pricing"), List.copyOf(held.keySet()));
        assertEquals("b2", held.get("pricing").commit);
        assertTrue(target.check().isEmpty(), String.valueOf(target.check()));
        List<RuleTarget.Published> history = target.history();
        assertEquals(4, history.size(), "every record, oldest first: two files, a new pricing, and fees stopped");
        assertEquals("a1", history.get(0).envelope.commit);
        assertTrue(history.get(3).envelope == null, "the stop is a tombstone");
    }

    @Test
    @DisplayName("a topic that is not compacted, or has several partitions, is reported")
    void wrongTopicIsReported() throws Exception {
        KafkaTarget target = new KafkaTarget("wrong", broker.getBrokersAsString(), "dmn-rules-wrong", Map.of(), 20);
        List<String> problems = target.check();
        assertEquals(2, problems.size(), String.valueOf(problems));
        assertTrue(target.describe().toString().contains("dmn-rules-wrong"));
        assertTrue(!target.describe().containsKey("properties"), "client settings, and so secrets, are never described");
    }

    @Test
    @DisplayName("an unreachable server fails within its timeout, with a reason")
    void unreachableFailsFast() {
        KafkaTarget target = new KafkaTarget("down", "127.0.0.1:1", "dmn-rules-it", Map.of(), 2);
        long started = System.nanoTime();
        Exception failure = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, target::readBack);
        assertTrue((System.nanoTime() - started) / 1_000_000_000 < 20, "took too long");
        assertTrue(failure.getMessage() != null);
    }
}
