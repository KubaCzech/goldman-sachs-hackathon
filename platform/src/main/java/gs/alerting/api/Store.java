package gs.alerting.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gs.alerting.core.Config;
import gs.alerting.core.Json;
import gs.alerting.stream.AlertTopology;
import gs.alerting.util.Db;
import gs.alerting.util.Env;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Configuration store (SPEC D3): Postgres is the record; every write is then published to the compacted
 * cfg topic, which is what the stream processor reads. {@link #republishAll()} reconciles on startup.
 */
public final class Store {
    static KafkaProducer<String, String> producer;
    /** In-memory view fed from the cfg topic (so it is correct on every API replica). */
    static final Config registry = new Config();

    private Store() {}

    static void init() { producer = new KafkaProducer<>(Env.producer(false)); }

    public static ObjectNode get(String kind, String id) {
        if (id == null) return null;
        ObjectNode r = Db.one("SELECT doc FROM config WHERE kind = ? AND id = ?", kind, id);
        return r == null ? null : (ObjectNode) r.get("doc");
    }

    public static List<ObjectNode> list(String kind) {
        List<ObjectNode> out = new ArrayList<>();
        for (ObjectNode r : Db.query("SELECT doc FROM config WHERE kind = ? ORDER BY id", kind)) out.add((ObjectNode) r.get("doc"));
        return out;
    }

    public static void save(String kind, ObjectNode doc) {
        String id = doc.get("id").asText();
        Db.exec("INSERT INTO config (kind, id, team_id, doc, updated_at) VALUES (?, ?, ?, ?::jsonb, ?) "
                + "ON CONFLICT (kind, id) DO UPDATE SET team_id = EXCLUDED.team_id, doc = EXCLUDED.doc, updated_at = EXCLUDED.updated_at",
                kind, id, Json.text(doc, "teamId"), doc, System.currentTimeMillis());
        publish(kind, id, doc);
    }

    public static void delete(String kind, String id) {
        Db.exec("DELETE FROM config WHERE kind = ? AND id = ?", kind, id);
        publish(kind, id, null);
    }

    static void publish(String kind, String id, ObjectNode doc) {
        String key = kind + ":" + id;
        String value = doc == null ? null : Json.str(doc);
        registry.apply(key, value);
        try { producer.send(new ProducerRecord<>(AlertTopology.CFG_TOPIC, key, value)).get(); }
        catch (Exception e) { throw new RuntimeException("Could not publish configuration: " + e.getMessage(), e); }
    }

    static void republishAll() {
        for (ObjectNode r : Db.query("SELECT kind, id, doc FROM config")) publish(r.get("kind").asText(), r.get("id").asText(), (ObjectNode) r.get("doc"));
    }

    public static String slug(String name) {
        String s = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (s.isEmpty()) s = "item";
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    public static String uniqueId(String kind, String name) {
        String base = slug(name), id = base;
        for (int i = 2; get(kind, id) != null; i++) id = base + "-" + i;
        return id;
    }

    /** SPEC 5.4: (requested sources or all) ∩ (owned by the team ∪ granted to the team). */
    static ArrayNode allowedSources(ObjectNode rule, List<ObjectNode> sources) {
        String team = Json.text(rule, "teamId");
        Set<String> wanted = new HashSet<>(Json.strings(rule.get("sourceIds")));
        ArrayNode out = Json.arr();
        for (ObjectNode s : sources) {
            String sid = Json.text(s, "id");
            boolean permitted = team != null && (team.equals(Json.text(s, "teamId")) || Json.strings(s.get("grantedTeamIds")).contains(team));
            if (permitted && (wanted.isEmpty() || wanted.contains(sid))) out.add(sid);
        }
        return out;
    }

    /** After a source or grant change, recompute and republish every rule whose permitted sources changed. */
    static void recomputeRules() {
        List<ObjectNode> sources = list("source");
        for (ObjectNode rule : list("rule")) {
            ArrayNode now = allowedSources(rule, sources);
            JsonNode before = rule.get("allowedSources");
            if (!now.equals(before)) { rule.set("allowedSources", now); save("rule", rule); }
        }
    }

    static void audit(String userId, String action, String resourceType, String resourceId, String alertId, ObjectNode detail) {
        Db.exec("INSERT INTO audit_log (ts, user_id, action, resource_type, resource_id, alert_id, detail) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)",
                System.currentTimeMillis(), userId, action, resourceType, resourceId, alertId, detail == null ? "{}" : Json.str(detail));
    }
}
