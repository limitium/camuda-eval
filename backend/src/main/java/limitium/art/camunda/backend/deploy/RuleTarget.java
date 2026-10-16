package limitium.art.camunda.backend.deploy;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** A server this instance deploys to: one topic, keyed by domain. */
public interface RuleTarget {

    String name();

    /** What may be shown about this server: never its credentials. */
    Map<String, Object> describe();

    /** Publishes every envelope, then a tombstone for each name in {@code stop}; all sent before it returns. */
    void publish(List<Envelope> envelopes, Collection<String> stop) throws Exception;

    /** What the topic holds now, by domain: read back, never remembered. */
    Map<String, Envelope> readBack() throws Exception;

    /** One record as published, oldest first: an envelope, or a tombstone (null) that stopped a file. */
    class Published {
        public String key;
        public Envelope envelope;
        /** When the record was written (the broker's timestamp). */
        public String at;
        public long offset;
    }

    /** Every record still on the topic, oldest first: what was deployed here, and when. */
    List<Published> history() throws Exception;

    /** Problems with how the topic is set up (not compacted, several partitions); empty when sound. */
    List<String> check() throws Exception;
}
