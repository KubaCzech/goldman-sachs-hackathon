package gs.alerting.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gs.alerting.core.AlertEngine;
import gs.alerting.core.Config;
import gs.alerting.core.Formats;
import gs.alerting.core.Event;
import gs.alerting.core.Json;
import gs.alerting.core.Normaliser;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.Stores;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * The one fixed topology (SPEC 8). Rules, schemas, teams and users are data read from the compacted
 * {@code cfg} topic through a global store; nothing here changes when a rule is edited.
 */
public final class AlertTopology {
    public static final String CFG_TOPIC = "cfg";
    public static final String MATCHES_TOPIC = "alerting.matches";
    public static final String COMMANDS_TOPIC = "alert.commands";
    public static final String ALERTS_TOPIC = "alerts";
    public static final String NOTIFICATIONS_TOPIC = "notifications";
    public static final String DLQ_TOPIC = "ingest.dlq";
    public static final Pattern INGEST_PATTERN = Pattern.compile("ingest\\.(?!dlq$).+");

    public static final AtomicLong EVENTS_IN = new AtomicLong(), MATCHES = new AtomicLong(), DLQ = new AtomicLong(),
            UNKNOWN_SOURCE = new AtomicLong(), ALERTS_OUT = new AtomicLong(), NOTIFICATIONS_OUT = new AtomicLong();

    private AlertTopology() {}

    public static Topology build(Config registry) {
        Topology t = new Topology();
        t.addGlobalStore(
                Stores.keyValueStoreBuilder(Stores.inMemoryKeyValueStore("cfg-store"), Serdes.String(), Serdes.String()).withLoggingDisabled(),
                "cfg-source", Serdes.String().deserializer(), Serdes.String().deserializer(), CFG_TOPIC,
                "cfg-updater", () -> new CfgUpdater(registry));

        t.addSource("ingest", Serdes.ByteArray().deserializer(), Serdes.ByteArray().deserializer(), INGEST_PATTERN);
        t.addProcessor("match", () -> new NormaliseAndMatch(registry), "ingest");
        t.addSink("dlq-sink", DLQ_TOPIC, Serdes.String().serializer(), Serdes.ByteArray().serializer(), "match");
        t.addSink("matches-sink", MATCHES_TOPIC, Serdes.String().serializer(), Serdes.String().serializer(), "match");

        t.addSource("alert-in", Serdes.String().deserializer(), Serdes.String().deserializer(), MATCHES_TOPIC, COMMANDS_TOPIC);
        t.addProcessor("alert", () -> new AlertStateProcessor(registry), "alert-in");
        t.addStateStore(Stores.keyValueStoreBuilder(Stores.persistentKeyValueStore("alert-state"), Serdes.String(), Serdes.String()), "alert");
        t.addSink("alerts-sink", ALERTS_TOPIC, Serdes.String().serializer(), Serdes.String().serializer(), "alert");
        t.addSink("notifications-sink", NOTIFICATIONS_TOPIC, Serdes.String().serializer(), Serdes.String().serializer(), "alert");
        return t;
    }

    /** Rebuild the registry from the global store; covers restore, which may bypass the update processor. */
    static void loadRegistry(Config registry, KeyValueStore<String, String> store) {
        try (KeyValueIterator<String, String> it = store.all()) {
            while (it.hasNext()) {
                KeyValue<String, String> kv = it.next();
                registry.apply(kv.key, kv.value);
            }
        }
    }

    /** Keeps the global store and the compiled registry in step with the cfg topic. */
    static final class CfgUpdater implements Processor<String, String, Void, Void> {
        private final Config registry;
        private KeyValueStore<String, String> store;

        CfgUpdater(Config registry) { this.registry = registry; }

        @Override
        public void init(ProcessorContext<Void, Void> ctx) {
            store = ctx.getStateStore("cfg-store");
            loadRegistry(registry, store);
        }

        @Override
        public void process(Record<String, String> rec) {
            if (rec.key() == null) return;
            if (rec.value() == null) store.delete(rec.key()); else store.put(rec.key(), rec.value());
            registry.apply(rec.key(), rec.value());
        }
    }

    /** Stateless: raw bytes → envelope → one MATCH per matching rule, keyed by alert key. */
    static final class NormaliseAndMatch implements Processor<byte[], byte[], String, Object> {
        private final Config registry;
        private ProcessorContext<String, Object> ctx;

        NormaliseAndMatch(Config registry) { this.registry = registry; }

        @Override
        public void init(ProcessorContext<String, Object> ctx) {
            this.ctx = ctx;
            loadRegistry(registry, ctx.getStateStore("cfg-store"));
        }

        @Override
        public void process(Record<byte[], byte[]> rec) {
            String topic = ctx.recordMetadata().map(m -> m.topic()).orElse("");
            String sourceId = topic.startsWith("ingest.") ? topic.substring(7) : topic;
            EVENTS_IN.incrementAndGet();
            Config.Snapshot cfg = registry.snapshot();
            if (!cfg.sources.containsKey(sourceId)) { UNKNOWN_SOURCE.incrementAndGet(); return; }
            if (rec.value() == null) return;
            // Any supported format: the source's setting wins, then the sender's Content-Type, then detection.
            String format = Json.text(cfg.sources.get(sourceId), "format");
            Header ct = rec.headers() == null ? null : rec.headers().lastHeader("content-type");
            String contentType = ct == null || ct.value() == null ? null : new String(ct.value(), StandardCharsets.UTF_8);
            List<ObjectNode> events;
            try {
                events = Formats.parse(rec.value(), format, contentType).events;
            } catch (RuntimeException ex) {   // not text at all, or not the format the source insists on
                DLQ.incrementAndGet();
                ctx.forward(new Record<String, Object>(sourceId, rec.value(), rec.timestamp()), "dlq-sink");
                return;
            }
            long ingestTs = rec.timestamp();
            Header h = rec.headers() == null ? null : rec.headers().lastHeader("ingest-ts");
            if (h != null && h.value() != null) {
                try { ingestTs = Long.parseLong(new String(h.value(), StandardCharsets.US_ASCII)); } catch (NumberFormatException ignore) { }
            }
            long now = ctx.currentSystemTimeMs();
            for (ObjectNode n : events) one(sourceId, n, ingestTs, now, rec.timestamp(), cfg);
        }

        private void one(String sourceId, JsonNode payload, long ingestTs, long now, long ts, Config.Snapshot cfg) {
            java.util.List<Config.Rule> rules = cfg.rulesFor(sourceId);
            if (rules.isEmpty()) return;
            Event e = Normaliser.normalise(sourceId, payload, ingestTs, now, cfg);
            String eventJson = null;
            for (Config.Rule r : rules) {
                boolean match, resolve = false;
                try {
                    match = r.filter.test(e);
                    if (!match && r.resolveWhen != null) { resolve = r.resolveWhen.test(e); }
                } catch (RuntimeException ex) { continue; }
                if (!match && !resolve) continue;
                if (eventJson == null) eventJson = Json.str(e.toJson());
                MATCHES.incrementAndGet();
                String value = "{\"kind\":\"MATCH\",\"ruleId\":" + Json.str(Json.M.getNodeFactory().textNode(r.id))
                        + ",\"resolve\":" + resolve + ",\"event\":" + eventJson + "}";
                ctx.forward(new Record<String, Object>(r.alertKey(e), value, ts), "matches-sink");
            }
        }
    }

    /** Stateful: one writer per alert key; merges matches and user commands. */
    static final class AlertStateProcessor implements Processor<String, String, String, String> {
        private final Config registry;
        private ProcessorContext<String, String> ctx;
        private KeyValueStore<String, String> store;

        AlertStateProcessor(Config registry) { this.registry = registry; }

        @Override
        public void init(ProcessorContext<String, String> ctx) {
            this.ctx = ctx;
            this.store = ctx.getStateStore("alert-state");
            loadRegistry(registry, ctx.getStateStore("cfg-store"));
        }

        @Override
        public void process(Record<String, String> rec) {
            if (rec.key() == null || rec.value() == null) return;
            JsonNode in;
            try { in = Json.M.readTree(rec.value()); } catch (Exception ex) { return; }
            String prev = store.get(rec.key());
            ObjectNode state = null;
            if (prev != null) { try { state = (ObjectNode) Json.M.readTree(prev); } catch (Exception ignore) { } }
            long now = ctx.currentSystemTimeMs();
            AlertEngine.Result res;
            if ("COMMAND".equals(Json.text(in, "kind"))) {
                res = AlertEngine.onCommand(state, in, now);
            } else {
                Config.Snapshot cfg = registry.snapshot();
                Config.Rule rule = cfg.rules.get(Json.text(in, "ruleId", ""));
                if (rule == null || !rule.enabled) return;
                res = AlertEngine.onMatch(state, rule, Event.fromJson(in.get("event")), in.path("resolve").asBoolean(false), cfg, rec.timestamp(), now);
            }
            store.put(rec.key(), Json.str(res.state));
            if (res.alert != null) {
                ALERTS_OUT.incrementAndGet();
                ctx.forward(new Record<>(Json.text(res.alert, "alertId"), Json.str(res.alert), rec.timestamp()), "alerts-sink");
            }
            for (ObjectNode n : res.notifications) {
                NOTIFICATIONS_OUT.incrementAndGet();
                ctx.forward(new Record<>(Json.text(n, "userId"), Json.str(n), rec.timestamp()), "notifications-sink");
            }
        }
    }
}
