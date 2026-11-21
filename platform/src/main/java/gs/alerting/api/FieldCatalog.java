package gs.alerting.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gs.alerting.api.Auth.Caller;
import gs.alerting.core.Config;
import gs.alerting.core.Event;
import gs.alerting.core.Formats;
import gs.alerting.core.Json;
import gs.alerting.core.Normaliser;
import gs.alerting.util.Env;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The fields a rule can use, for the sources it watches: the built-in envelope fields, the fields of the schemas those
 * sources use, and the fields (with the values seen) of the last events those sources actually sent. Lets the rule
 * editor offer only fields that exist, typed, with real example values.
 */
final class FieldCatalog {
    private static final int RECENT = 50;
    private static final long CACHE_MS = 10_000;
    private static final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(long at, List<Event> events) {}

    private FieldCatalog() {}

    /** Built-in fields every event has after normalisation. scope "alert" fields only make sense when searching alerts. */
    private static final String[][] CANONICAL = {
            {"severity", "severity", "event", "Normalised severity: critical, error, warning, info or unknown"},
            {"type", "string", "event", "What kind of event it is (found by the schema or guessed)"},
            {"service", "string", "event", "Which system sent it"},
            {"source", "string", "event", "The source it arrived on"},
            {"schema", "string", "event", "The schema it was recognised as"},
            {"eventTime", "number", "event", "When it happened (epoch ms)"},
            {"status", "string", "alert", "Alert status: OPEN, ACKNOWLEDGED or RESOLVED"},
            {"count", "number", "alert", "How many events the alert has grouped"},
    };

    static ArrayNode catalog(Caller c, List<String> sourceIds) {
        Config.Snapshot cfg = Store.registry.snapshot();
        Map<String, ObjectNode> out = new LinkedHashMap<>();
        for (String[] f : CANONICAL) {
            ObjectNode o = Json.obj().put("path", f[0]).put("type", f[1]).put("scope", f[2]).put("origin", "built-in").put("description", f[3]);
            if (f[0].equals("severity")) o.set("values", Json.M.valueToTree(List.of("critical", "error", "warning", "info", "unknown")));
            if (f[0].equals("status")) o.set("values", Json.M.valueToTree(List.of("OPEN", "ACKNOWLEDGED", "RESOLVED")));
            out.put(f[0], o);
        }
        List<String> readable = new ArrayList<>();
        for (String id : sourceIds) if (canRead(c, cfg.sources.get(id))) readable.add(id);
        if (!readable.isEmpty()) ((ObjectNode) out.get("source")).set("values", Json.M.valueToTree(readable));

        // Which schemas apply: the sources' default schemas plus those the recent events were recognised as.
        Set<String> schemaIds = new LinkedHashSet<>();
        Map<String, List<Event>> recent = new LinkedHashMap<>();
        for (String id : readable) {
            String def = Json.text(cfg.sources.get(id), "defaultSchemaId");
            if (def != null && !def.isBlank()) schemaIds.add(def);
            List<Event> ev = recentEvents(id, cfg);
            recent.put(id, ev);
            for (Event e : ev) if (e.schemaId != null && !e.schemaId.equals("unknown")) schemaIds.add(e.schemaId);
        }
        if (sourceIds.isEmpty()) for (ObjectNode s : Store.list("schema")) schemaIds.add(Json.text(s, "id"));   // no source chosen: everything known

        for (String sid : schemaIds) {
            ObjectNode s = Store.get("schema", sid);
            if (s == null) continue;
            for (JsonNode f : s.path("fields")) {
                String p = Json.text(f, "path");
                if (p == null || p.isBlank()) continue;
                ObjectNode o = out.computeIfAbsent(p, k -> Json.obj().put("path", k).put("scope", "event").put("origin", "schema"));
                if (!o.has("type") && f.has("type")) o.put("type", Json.text(f, "type"));
                if (!o.has("example") && f.hasNonNull("example")) o.set("example", f.get("example"));
                addTo(o, "schemas", Json.text(s, "name", sid));
            }
        }

        // Observed: paths and values in what the sources really sent.
        for (Map.Entry<String, List<Event>> en : recent.entrySet()) {
            for (Event e : en.getValue()) {
                Map<String, JsonNode> leaves = new LinkedHashMap<>();
                leaves(e.payload, "", leaves, 0);
                leaves.put("type", e.type == null ? null : Json.M.getNodeFactory().textNode(e.type));
                leaves.put("service", e.service == null ? null : Json.M.getNodeFactory().textNode(e.service));
                for (Map.Entry<String, JsonNode> l : leaves.entrySet()) {
                    if (l.getValue() == null || l.getValue().isNull()) continue;
                    ObjectNode o = out.computeIfAbsent(l.getKey(), k -> Json.obj().put("path", k).put("scope", "event").put("origin", "observed"));
                    if (!o.has("type")) o.put("type", l.getValue().isNumber() ? "number" : l.getValue().isBoolean() ? "boolean" : "string");
                    if (!o.has("example")) o.set("example", l.getValue());
                    addTo(o, "seenIn", en.getKey());
                    if (!l.getValue().isNumber()) addValue(o, l.getValue().asText());
                    o.put("seen", o.path("seen").asInt(0) + 1);
                }
            }
        }
        ArrayNode arr = Json.arr();
        out.values().forEach(o -> {
            // A text field with a handful of distinct values is offered as a choice; free text stays possible.
            JsonNode v = o.get("values");
            if (v != null && v.size() > 12 && !"built-in".equals(Json.text(o, "origin"))) o.remove("values");
            arr.add(o);
        });
        return arr;
    }

    static boolean canRead(Caller c, JsonNode source) {
        if (source == null) return false;
        if (c.member(Json.text(source, "teamId"))) return true;
        for (String t : Json.strings(source.get("grantedTeamIds"))) if (c.roles.containsKey(t)) return true;
        return false;
    }

    private static void addTo(ObjectNode o, String key, String v) {
        ArrayNode a = o.has(key) ? (ArrayNode) o.get(key) : o.putArray(key);
        for (JsonNode x : a) if (x.asText().equals(v)) return;
        a.add(v);
    }

    private static void addValue(ObjectNode o, String v) {
        if (v.length() > 80) return;
        ArrayNode a = o.has("values") ? (ArrayNode) o.get("values") : o.putArray("values");
        if (a.size() > 13) return;
        for (JsonNode x : a) if (x.asText().equals(v)) return;
        a.add(v);
    }

    private static void leaves(JsonNode n, String prefix, Map<String, JsonNode> out, int depth) {
        if (n == null || depth > 8 || out.size() > 300) return;
        if (n.isObject()) n.fields().forEachRemaining(en -> leaves(en.getValue(), prefix.isEmpty() ? en.getKey() : prefix + "." + en.getKey(), out, depth + 1));
        else if (n.isArray()) { if (n.size() > 0) leaves(n.get(0), prefix + ".0", out, depth + 1); }
        else if (!prefix.isEmpty()) out.putIfAbsent(prefix, n);
    }

    /** The last few events of a source, read from its Kafka topic (cached briefly), parsed and normalised like the stream does. */
    static List<Event> recentEvents(String sourceId, Config.Snapshot cfg) {
        Cached hit = cache.get(sourceId);
        if (hit != null && System.currentTimeMillis() - hit.at() < CACHE_MS) return hit.events();
        List<Event> events = new ArrayList<>();
        Properties p = Env.base();
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        String topic = "ingest." + sourceId;
        String format = Json.text(cfg.sources.get(sourceId), "format");
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(p)) {
            List<PartitionInfo> parts = consumer.partitionsFor(topic, Duration.ofSeconds(2));
            if (parts == null || parts.isEmpty()) return events;
            List<TopicPartition> tps = parts.stream().map(i -> new TopicPartition(topic, i.partition())).toList();
            consumer.assign(tps);
            Map<TopicPartition, Long> end = consumer.endOffsets(tps), begin = consumer.beginningOffsets(tps);
            int per = Math.max(1, RECENT / tps.size());
            long wanted = 0;
            for (TopicPartition tp : tps) {
                long from = Math.max(begin.get(tp), end.get(tp) - per);
                consumer.seek(tp, from);
                wanted += end.get(tp) - from;
            }
            long deadline = System.currentTimeMillis() + 2000, got = 0;
            while (got < wanted && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<byte[], byte[]> r : consumer.poll(Duration.ofMillis(200))) {
                    got++;
                    if (r.value() == null) continue;
                    try {
                        String ct = r.headers().lastHeader("content-type") == null ? null : new String(r.headers().lastHeader("content-type").value());
                        for (ObjectNode ev : Formats.parse(r.value(), format, ct).events)
                            if (events.size() < RECENT * 2) events.add(Normaliser.normalise(sourceId, ev, r.timestamp(), System.currentTimeMillis(), cfg));
                    } catch (RuntimeException notText) { /* skip */ }
                }
            }
        } catch (Exception e) {
            return events;   // Kafka slow or topic missing: the editor still has schema and built-in fields
        }
        cache.put(sourceId, new Cached(System.currentTimeMillis(), events));
        return events;
    }

    static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String s : csv.split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }
}
