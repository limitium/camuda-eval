package limitium.art.camunda.backend.deploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import limitium.art.camunda.backend.service.VersionedFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * A folder standing in for a topic, one file per domain: for a laptop or a test with no broker.
 * Same contract as Kafka — a tombstone removes the domain — so nothing above it can tell.
 */
public class FileTarget implements RuleTarget {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String name;
    private final Path dir;

    public FileTarget(String name, Path dir) {
        this.name = name;
        this.dir = dir;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Map<String, Object> describe() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name);
        d.put("type", "file");
        d.put("topic", dir.getFileName().toString());
        d.put("where", dir.toString());
        return d;
    }

    @Override
    public synchronized void publish(List<Envelope> envelopes, Collection<String> stop) throws IOException {
        Files.createDirectories(dir);
        for (Envelope envelope : envelopes) {
            VersionedFiles.writeAtomically(dir.resolve(envelope.name + ".json"), JSON.writeValueAsBytes(envelope));
        }
        for (String domain : stop) {
            Files.deleteIfExists(dir.resolve(domain + ".json"));
        }
        // The folder keeps a log too, so it has a history like a topic does.
        StringBuilder log = new StringBuilder();
        String at = java.time.Instant.now().toString();
        for (Envelope envelope : envelopes) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("key", envelope.name);
            line.put("at", at);
            line.put("envelope", envelope.summary());
            log.append(JSON.writeValueAsString(line)).append('\n');
        }
        for (String domain : stop) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("key", domain);
            line.put("at", at);
            log.append(JSON.writeValueAsString(line)).append('\n');
        }
        Files.write(dir.resolve(".history.jsonl"), log.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    @Override
    public synchronized List<Published> history() throws IOException {
        List<Published> history = new java.util.ArrayList<>();
        Path log = dir.resolve(".history.jsonl");
        if (!Files.exists(log)) {
            return history;
        }
        long offset = 0;
        for (String line : Files.readAllLines(log)) {
            if (line.isBlank()) {
                continue;
            }
            com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(line);
            Published p = new Published();
            p.key = node.get("key").asText();
            p.at = node.has("at") ? node.get("at").asText() : null;
            p.offset = offset++;
            p.envelope = node.has("envelope") ? JSON.treeToValue(node.get("envelope"), Envelope.class) : null;
            history.add(p);
        }
        return history;
    }

    @Override
    public synchronized Map<String, Envelope> readBack() throws IOException {
        if (!Files.isDirectory(dir)) {
            return Collections.emptyMap();
        }
        Map<String, Envelope> held = new TreeMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String fileName = file.getFileName().toString();
                if (fileName.endsWith(".json") && !fileName.startsWith(".")) {
                    Envelope envelope = JSON.readValue(file.toFile(), Envelope.class);
                    held.put(envelope.name, envelope);
                }
            }
        }
        return held;
    }

    @Override
    public List<String> check() {
        return Collections.emptyList();
    }
}
