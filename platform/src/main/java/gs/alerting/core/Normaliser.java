package gs.alerting.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds the normalised envelope from a raw payload (SPEC 5.2, 5.3) and infers schemas from samples. */
public final class Normaliser {
    static final List<String> SEVERITY_PATHS = List.of("severity", "level", "labels.severity", "priority", "meta.severity");
    static final List<String> TIME_PATHS = List.of("timestamp", "ts", "time", "@timestamp", "startsAt", "meta.ts", "meta.timestamp");
    static final List<String> TYPE_PATHS = List.of("type", "alertname", "meta.schema", "event", "eventType", "name");
    static final List<String> SERVICE_PATHS = List.of("service", "labels.service", "meta.producer", "workflowName", "app");

    private Normaliser() {}

    public static Event normalise(String sourceId, JsonNode payload, long ingestTs, long now, Config.Snapshot cfg) {
        Event e = new Event();
        e.eventId = UUID.randomUUID().toString();
        e.sourceId = sourceId;
        e.payload = payload;
        e.receivedAt = now;
        e.ingestTs = ingestTs > 0 ? ingestTs : now;
        JsonNode source = cfg.sources.get(sourceId);
        e.ownerTeamId = Json.text(source, "teamId");

        Config.Schema schema = null;
        for (Config.Schema s : cfg.matchSchemas) {
            if (s.match.test(e)) { schema = s; break; }
        }
        if (schema == null) schema = cfg.schemas.get(Json.text(source, "defaultSchemaId", ""));
        e.schemaId = schema == null ? "unknown" : schema.id;
        JsonNode mapping = schema == null ? null : schema.mapping;

        // severity
        JsonNode sm = mapping == null ? null : mapping.get("severity");
        String rawSev = mapped(payload, sm, SEVERITY_PATHS);
        if (rawSev != null && sm != null && sm.path("map").isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = sm.get("map").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> en = it.next();
                if (en.getKey().equalsIgnoreCase(rawSev)) { rawSev = en.getValue().asText(); break; }
            }
        }
        e.severity = Severity.normalise(rawSev);
        e.type = mapped(payload, mapping == null ? null : mapping.get("type"), TYPE_PATHS);
        e.service = mapped(payload, mapping == null ? null : mapping.get("service"), SERVICE_PATHS);

        // timestamp
        JsonNode tm = mapping == null ? null : mapping.get("timestamp");
        JsonNode tv = null;
        String tpath = Json.text(tm, "path");
        if (tpath != null && !tpath.isEmpty()) tv = Json.path(payload, tpath);
        if (tv == null) for (String p : TIME_PATHS) { tv = Json.path(payload, p); if (tv != null) break; }
        e.eventTime = parseTime(tv, Json.text(tm, "format"), now);
        return e;
    }

    private static String mapped(JsonNode payload, JsonNode m, List<String> fallbacks) {
        if (m != null && m.isObject()) {
            String c = Json.text(m, "const");
            if (c != null && !c.isEmpty()) return c;
            String p = Json.text(m, "path");
            if (p != null && !p.isEmpty()) {
                String v = Json.text(Json.path(payload, p));
                if (v != null) return v;
            }
        }
        for (String p : fallbacks) {
            JsonNode v = Json.path(payload, p);
            if (v != null && v.isValueNode()) return v.asText();
        }
        return null;
    }

    static long parseTime(JsonNode v, String format, long dflt) {
        if (v == null) return dflt;
        try {
            if (v.isNumber() || (v.isTextual() && v.asText().matches("\\d{9,16}"))) {
                long n = v.isNumber() ? v.asLong() : Long.parseLong(v.asText());
                if ("epoch_s".equals(format)) return n * 1000;
                if ("epoch_ms".equals(format)) return n;
                return n >= 1_000_000_000_000L ? n : n * 1000;
            }
            String s = v.asText();
            try { return Instant.parse(s).toEpochMilli(); } catch (Exception ignore) { }
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (Exception ex) {
            return dflt;
        }
    }

    // ---------------------------------------------------------------- schema inference

    /** Flatten a sample into field descriptors and suggest a canonical mapping. */
    public static ObjectNode infer(JsonNode sample) {
        ObjectNode out = Json.obj();
        ArrayNode fields = out.putArray("fields");
        flatten("", sample, fields, 0);
        ObjectNode mapping = out.putObject("mapping");
        String sev = first(sample, SEVERITY_PATHS), time = first(sample, TIME_PATHS), type = first(sample, TYPE_PATHS), svc = first(sample, SERVICE_PATHS);
        ObjectNode s = mapping.putObject("severity");
        s.put("path", sev);
        s.putNull("const");
        s.putObject("map");
        ObjectNode t = mapping.putObject("timestamp");
        t.put("path", time);
        JsonNode tv = time == null ? null : Json.path(sample, time);
        t.put("format", tv == null ? "iso" : tv.isNumber() ? (tv.asLong() >= 1_000_000_000_000L ? "epoch_ms" : "epoch_s") : "iso");
        mapping.putObject("type").put("path", type).putNull("const");
        mapping.putObject("service").put("path", svc).putNull("const");
        return out;
    }

    private static String first(JsonNode sample, List<String> paths) {
        for (String p : paths) { JsonNode v = Json.path(sample, p); if (v != null && v.isValueNode()) return p; }
        return null;
    }

    private static void flatten(String prefix, JsonNode n, ArrayNode out, int depth) {
        if (n == null || depth > 8 || out.size() > 300) return;
        if (n.isObject()) {
            n.fields().forEachRemaining(en -> flatten(prefix.isEmpty() ? en.getKey() : prefix + "." + en.getKey(), en.getValue(), out, depth + 1));
        } else if (n.isArray()) {
            if (n.size() > 0) flatten(prefix + ".0", n.get(0), out, depth + 1);
        } else if (!prefix.isEmpty()) {
            ObjectNode f = out.addObject();
            f.put("path", prefix);
            f.put("type", n.isNumber() ? "number" : n.isBoolean() ? "boolean" : "string");
            String ex = n.asText();
            f.put("example", ex.length() > 80 ? ex.substring(0, 80) : ex);
        }
    }
}
