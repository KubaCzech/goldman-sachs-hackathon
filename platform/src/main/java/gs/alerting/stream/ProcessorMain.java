package gs.alerting.stream;

import gs.alerting.core.Config;
import gs.alerting.util.Env;
import gs.alerting.util.Health;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** ROLE=processor: runs the fixed topology with exactly-once processing. */
public final class ProcessorMain {
    private static final Logger log = LoggerFactory.getLogger(ProcessorMain.class);

    public static void run() throws Exception {
        log.info("Waiting for topics on {}", Env.bootstrap());
        Env.awaitTopics(List.of(AlertTopology.CFG_TOPIC, AlertTopology.MATCHES_TOPIC, AlertTopology.COMMANDS_TOPIC,
                AlertTopology.ALERTS_TOPIC, AlertTopology.NOTIFICATIONS_TOPIC, AlertTopology.DLQ_TOPIC));

        Properties p = Env.base();
        p.put(StreamsConfig.APPLICATION_ID_CONFIG, "alerting-processor");
        p.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, Env.get("PROCESSING_GUARANTEE", StreamsConfig.EXACTLY_ONCE_V2));
        p.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, Env.getInt("COMMIT_INTERVAL_MS", 100));
        p.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, Env.getInt("STREAM_THREADS", 2));
        p.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, (int) Env.replication());
        p.put(StreamsConfig.STATE_DIR_CONFIG, Env.get("STATE_DIR", "/tmp/kafka-streams"));
        p.put(StreamsConfig.NUM_STANDBY_REPLICAS_CONFIG, Env.getInt("STANDBY_REPLICAS", 0));
        // New ingest.<source> topics must be noticed quickly by the pattern subscription.
        p.put(StreamsConfig.consumerPrefix(ConsumerConfig.METADATA_MAX_AGE_CONFIG), 5000);
        p.put(StreamsConfig.consumerPrefix(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG), "earliest");
        p.put(StreamsConfig.producerPrefix(ProducerConfig.LINGER_MS_CONFIG), Env.getInt("PRODUCER_LINGER_MS", 5));
        p.put(StreamsConfig.producerPrefix(ProducerConfig.COMPRESSION_TYPE_CONFIG), "lz4");

        KafkaStreams streams = new KafkaStreams(AlertTopology.build(Config.GLOBAL), p);
        streams.setUncaughtExceptionHandler(e -> {
            log.error("Stream thread failed; replacing it", e);
            return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> streams.close(Duration.ofSeconds(20))));

        Health.start(Env.getInt("PORT", 8081), () -> streams.state() == KafkaStreams.State.RUNNING, () -> {
            Map<String, Number> m = new LinkedHashMap<>();
            m.put("alerting_events_in_total", AlertTopology.EVENTS_IN.get());
            m.put("alerting_matches_total", AlertTopology.MATCHES.get());
            m.put("alerting_alert_updates_total", AlertTopology.ALERTS_OUT.get());
            m.put("alerting_notifications_total", AlertTopology.NOTIFICATIONS_OUT.get());
            m.put("alerting_dead_letter_total", AlertTopology.DLQ.get());
            m.put("alerting_unknown_source_total", AlertTopology.UNKNOWN_SOURCE.get());
            m.put("alerting_config_documents", Config.GLOBAL.size());
            m.put("alerting_streams_running", streams.state() == KafkaStreams.State.RUNNING ? 1 : 0);
            return m;
        });
        streams.start();
        log.info("Processor started");
    }
}
