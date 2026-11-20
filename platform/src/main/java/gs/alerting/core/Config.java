package gs.alerting.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory view of the compacted {@code cfg} topic (SPEC 8, ConfigRegistry).
 * Raw documents are kept per key; an immutable compiled snapshot is rebuilt lazily after changes.
 */
public final class Config {
    public static final Config GLOBAL = new Config();

    public static final class Rule {
        public String id, teamId, name, severityOverride, titleTpl, bodyTpl, color, runbookId;
        public boolean enabled;
        public Filter.Pred filter, resolveWhen;
        public List<String> groupBy, channels, fields;
        public Set<String> allowedSources;
        public int windowCount, windowSeconds, renotifyMinutes;
        public JsonNode targets, doc;

        public static Rule compile(JsonNode d) {
            Rule r = new Rule();
            r.doc = d;
            r.id = Json.text(d, "id");
            r.teamId = Json.text(d, "teamId");
            r.name = Json.text(d, "name", r.id);
            r.enabled = d.path("enabled").asBoolean(true);
            r.filter = Filter.compile(d.get("filter"));
            JsonNode rw = d.get("resolveWhen");
            r.resolveWhen = rw == null || rw.isNull() ? null : Filter.compile(rw);
            r.groupBy = Json.strings(d.get("groupBy"));
            r.channels = Json.strings(d.get("channels"));
            r.allowedSources = new HashSet<>(Json.strings(d.get("allowedSources")));
            JsonNode w = d.get("window");
            if (w != null && w.isObject() && w.path("count").asInt() > 1 && w.path("seconds").asInt() > 0) {
                r.windowCount = w.path("count").asInt();
                r.windowSeconds = w.path("seconds").asInt();
            }
            r.severityOverride = Json.text(d, "severity");
            if (r.severityOverride != null && r.severityOverride.isBlank()) r.severityOverride = null;
            JsonNode p = d.path("presentation");
            r.titleTpl = Json.text(p, "titleTemplate", "{{type}} from {{service}}");
            r.bodyTpl = Json.text(p, "bodyTemplate", "");
            r.fields = Json.strings(p.get("fields"));
            r.color = Json.text(p, "color");
            if (r.color != null && r.color.isBlank()) r.color = null;
            r.runbookId = Json.text(d, "runbookId");
            if (r.runbookId != null && r.runbookId.isBlank()) r.runbookId = null;
            r.renotifyMinutes = d.path("renotifyMinutes").asInt(0);
            r.targets = d.path("targets");
            return r;
        }

        public String alertKey(Event e) {
            StringBuilder sb = new StringBuilder();
            for (String g : groupBy) sb.append(Json.text(e.resolve(g))).append('\u0001');
            return id + "|" + Json.sha1Hex(sb.toString()).substring(0, 16);
        }
    }

    public static final class Schema {
        public String id;
        public int priority;
        public Filter.Pred match;
        public JsonNode mapping;

        static Schema compile(JsonNode d) {
            Schema s = new Schema();
            s.id = Json.text(d, "id");
            s.priority = d.path("priority").asInt(0);
            JsonNode m = d.get("match");
            s.match = m == null || m.isNull() ? null : Filter.compile(m);
            s.mapping = d.path("mapping");
            return s;
        }
    }

    public static final class Snapshot {
        public final Map<String, JsonNode> sources = new HashMap<>();
        public final Map<String, Schema> schemas = new HashMap<>();
        public final List<Schema> matchSchemas = new ArrayList<>();
        public final Map<String, Rule> rules = new HashMap<>();
        public final Map<String, List<Rule>> rulesBySource = new HashMap<>();
        public final Map<String, JsonNode> teams = new HashMap<>();
        public final Map<String, JsonNode> users = new HashMap<>();
        public final Map<String, JsonNode> runbooks = new HashMap<>();

        public List<Rule> rulesFor(String sourceId) { return rulesBySource.getOrDefault(sourceId, Collections.emptyList()); }
    }

    private final Map<String, JsonNode> docs = new ConcurrentHashMap<>();
    private volatile Snapshot snapshot = new Snapshot();
    private volatile boolean dirty = false;

    /** Apply one cfg record. A null value is a tombstone. */
    public void apply(String key, String value) {
        if (key == null) return;
        if (value == null || value.isEmpty()) docs.remove(key);
        else {
            try { docs.put(key, Json.M.readTree(value)); } catch (Exception e) { return; }
        }
        dirty = true;
    }

    public void clear() { docs.clear(); dirty = true; }

    public int size() { return docs.size(); }

    public Snapshot snapshot() {
        if (dirty) rebuild();
        return snapshot;
    }

    private synchronized void rebuild() {
        if (!dirty) return;
        dirty = false;
        Snapshot s = new Snapshot();
        for (Map.Entry<String, JsonNode> en : docs.entrySet()) {
            String key = en.getKey();
            int c = key.indexOf(':');
            if (c < 0) continue;
            String kind = key.substring(0, c), id = key.substring(c + 1);
            JsonNode d = en.getValue();
            try {
                switch (kind) {
                    case "source" -> s.sources.put(id, d);
                    case "team" -> s.teams.put(id, d);
                    case "user" -> s.users.put(id, d);
                    case "runbook" -> s.runbooks.put(id, d);
                    case "schema" -> {
                        Schema sc = Schema.compile(d);
                        s.schemas.put(id, sc);
                        if (sc.match != null) s.matchSchemas.add(sc);
                    }
                    case "rule" -> {
                        Rule r = Rule.compile(d);
                        s.rules.put(id, r);
                        if (r.enabled) for (String src : r.allowedSources) s.rulesBySource.computeIfAbsent(src, k -> new ArrayList<>()).add(r);
                    }
                    default -> { }
                }
            } catch (RuntimeException ex) {
                // A broken document must never stop the pipeline; the API validates before publishing.
                System.err.println("cfg: skipping invalid " + key + ": " + ex.getMessage());
            }
        }
        s.matchSchemas.sort(Comparator.comparingInt((Schema x) -> -x.priority).thenComparing(x -> x.id));
        snapshot = s;
    }
}
