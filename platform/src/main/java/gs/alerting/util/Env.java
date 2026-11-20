package gs.alerting.util;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/** Environment configuration and Kafka client helpers shared by all roles. */
public final class Env {
    public static final int PARTITIONS = 6;

    private Env() {}

    public static String get(String k, String d) { String v = System.getenv(k); return v == null || v.isEmpty() ? d : v; }
    public static int getInt(String k, int d) { return Integer.parseInt(get(k, String.valueOf(d))); }
    public static String bootstrap() { return get("KAFKA_BOOTSTRAP", "localhost:29092"); }
    public static short replication() { return (short) getInt("REPLICATION", 3); }

    public static Properties base() {
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap());
        return p;
    }

    public static Properties producer(boolean bytesValue) {
        Properties p = base();
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, bytesValue ? ByteArraySerializer.class.getName() : StringSerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        p.put(ProducerConfig.LINGER_MS_CONFIG, get("PRODUCER_LINGER_MS", "5"));
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        return p;
    }

    public static Properties consumer(String group, boolean fromEarliest) {
        Properties p = base();
        p.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, fromEarliest ? "earliest" : "latest");
        return p;
    }

    /** Create a topic if it does not exist. */
    public static void ensureTopic(Admin admin, String name, int partitions, Map<String, String> configs) throws Exception {
        NewTopic t = new NewTopic(name, partitions, replication());
        Map<String, String> c = new HashMap<>(configs);
        c.putIfAbsent("min.insync.replicas", String.valueOf(Math.max(1, Math.min(2, replication() - 1 == 0 ? 1 : replication() - 1))));
        t.configs(c);
        try { admin.createTopics(List.of(t)).all().get(); }
        catch (ExecutionException e) { if (!(e.getCause() instanceof TopicExistsException)) throw e; }
    }

    /** Block until all the given topics exist (other roles create them). */
    public static void awaitTopics(Collection<String> topics) throws Exception {
        try (Admin admin = Admin.create(base())) {
            while (true) {
                try {
                    Set<String> names = admin.listTopics().names().get();
                    if (names.containsAll(topics)) return;
                } catch (Exception e) { /* brokers not ready yet */ }
                Thread.sleep(1000);
            }
        }
    }
}
