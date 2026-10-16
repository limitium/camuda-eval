package limitium.art.camunda.backend.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * One Kafka topic, keyed by domain, compacted, one partition so the rules have a single order.
 *
 * <p>Clients are made per call and closed after it: deploys are rare, and nothing then holds a
 * connection (or a credential) longer than the action that needed it.
 */
public class KafkaTarget implements RuleTarget {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String name;
    private final String bootstrapServers;
    private final String topic;
    private final Map<String, String> clientProperties;
    private final Duration timeout;

    public KafkaTarget(String name, String bootstrapServers, String topic, Map<String, String> clientProperties, int timeoutSeconds) {
        this.name = name;
        this.bootstrapServers = bootstrapServers;
        this.topic = topic;
        this.clientProperties = clientProperties == null ? Collections.emptyMap() : clientProperties;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Map<String, Object> describe() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name);
        d.put("type", "kafka");
        d.put("topic", topic);
        d.put("where", bootstrapServers);
        return d;
    }

    private Properties base() {
        Properties p = new Properties();
        p.putAll(clientProperties);
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(AdminClientConfig.CLIENT_ID_CONFIG, "camunda-eval-" + name);
        return p;
    }

    @Override
    public void publish(List<Envelope> envelopes, Collection<String> stop) throws Exception {
        Properties p = base();
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, String.valueOf(timeout.toMillis()));
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, String.valueOf(Math.max(timeout.toMillis(), 30_000)));
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(Math.min(timeout.toMillis(), 30_000)));
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(p, new StringSerializer(), new StringSerializer())) {
            List<Future<RecordMetadata>> sent = new ArrayList<>();
            for (Envelope envelope : envelopes) {
                sent.add(producer.send(new ProducerRecord<>(topic, envelope.name, JSON.writeValueAsString(envelope))));
            }
            for (String domain : stop) {
                sent.add(producer.send(new ProducerRecord<>(topic, domain, null)));
            }
            producer.flush();
            for (Future<RecordMetadata> f : sent) {
                f.get(timeout.toMillis() * 2, TimeUnit.MILLISECONDS); // a failed send fails the deploy here
            }
        }
    }

    @Override
    public Map<String, Envelope> readBack() throws Exception {
        Properties p = base();
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, String.valueOf(timeout.toMillis()));
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic, timeout);
            Map<String, Envelope> held = new TreeMap<>();
            if (infos == null || infos.isEmpty()) {
                return held;
            }
            List<TopicPartition> partitions = new ArrayList<>();
            for (PartitionInfo info : infos) {
                partitions.add(new TopicPartition(topic, info.partition()));
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions, timeout);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!reached(consumer, end)) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("reading " + topic + " back took longer than " + timeout.getSeconds() + "s");
                }
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.key() == null) {
                        continue;
                    }
                    if (record.value() == null) {
                        held.remove(record.key());
                    } else {
                        held.put(record.key(), JSON.readValue(record.value(), Envelope.class));
                    }
                }
            }
            return held;
        }
    }

    @Override
    public List<Published> history() throws Exception {
        Properties p = base();
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, String.valueOf(timeout.toMillis()));
        List<Published> history = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic, timeout);
            if (infos == null || infos.isEmpty()) {
                return history;
            }
            List<TopicPartition> partitions = new ArrayList<>();
            for (PartitionInfo info : infos) {
                partitions.add(new TopicPartition(topic, info.partition()));
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions, timeout);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!reached(consumer, end)) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("reading " + topic + " took longer than " + timeout.getSeconds() + "s");
                }
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.key() == null) {
                        continue;
                    }
                    Published published = new Published();
                    published.key = record.key();
                    published.offset = record.offset();
                    published.at = java.time.Instant.ofEpochMilli(record.timestamp()).toString();
                    published.envelope = record.value() == null ? null : JSON.readValue(record.value(), Envelope.class).summary();
                    history.add(published);
                }
            }
        }
        history.sort(java.util.Comparator.comparingLong(x -> x.offset));
        return history;
    }

    private static boolean reached(KafkaConsumer<String, String> consumer, Map<TopicPartition, Long> end) {
        for (Map.Entry<TopicPartition, Long> e : end.entrySet()) {
            if (consumer.position(e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> check() throws Exception {
        List<String> problems = new ArrayList<>();
        try (Admin admin = Admin.create(base())) {
            Map<String, TopicDescription> topics = admin.describeTopics(Collections.singleton(topic))
                    .allTopicNames().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            TopicDescription description = topics.get(topic);
            if (description.partitions().size() != 1) {
                problems.add(topic + " has " + description.partitions().size()
                        + " partitions; with more than one, the rules have no single order");
            }
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
            Config config = admin.describeConfigs(Collections.singleton(resource)).all()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS).get(resource);
            String policy = config.get("cleanup.policy") == null ? "" : config.get("cleanup.policy").value();
            if (!policy.contains("compact")) {
                problems.add(topic + " is not compacted (cleanup.policy=" + policy
                        + "); old rules expire and what runs can no longer be read back");
            }
        }
        return problems;
    }
}
