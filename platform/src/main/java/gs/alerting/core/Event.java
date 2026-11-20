package gs.alerting.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/** Normalised event envelope (SPEC 5.3). Also the evaluation context for filters and templates. */
public final class Event {
    public String eventId;
    public String sourceId;
    public String ownerTeamId;
    public String schemaId;
    public long receivedAt;
    public long eventTime;
    public long ingestTs;
    public String severity = "unknown";
    public String type;
    public String service;
    public JsonNode payload;

    /** Resolve a field path: canonical names first, then {@code raw.} prefix, then the original payload. */
    public JsonNode resolve(String path) {
        switch (path) {
            case "source": return txt(sourceId);
            case "schema": return txt(schemaId);
            case "severity": return txt(severity);
            case "type": return type != null ? txt(type) : Json.path(payload, "type");
            case "service": return service != null ? txt(service) : Json.path(payload, "service");
            case "eventTime": return LongNode.valueOf(eventTime);
            case "receivedAt": return LongNode.valueOf(receivedAt);
            default:
        }
        if (path.startsWith("raw.")) return Json.path(payload, path.substring(4));
        return Json.path(payload, path);
    }

    private static JsonNode txt(String s) { return s == null ? null : TextNode.valueOf(s); }

    public ObjectNode toJson() {
        ObjectNode o = Json.obj();
        o.put("eventId", eventId).put("sourceId", sourceId).put("ownerTeamId", ownerTeamId).put("schemaId", schemaId)
                .put("receivedAt", receivedAt).put("eventTime", eventTime).put("ingestTs", ingestTs)
                .put("severity", severity).put("type", type).put("service", service);
        o.set("payload", payload);
        return o;
    }

    public static Event fromJson(JsonNode o) {
        Event e = new Event();
        e.eventId = Json.text(o, "eventId");
        e.sourceId = Json.text(o, "sourceId");
        e.ownerTeamId = Json.text(o, "ownerTeamId");
        e.schemaId = Json.text(o, "schemaId");
        e.receivedAt = o.path("receivedAt").asLong();
        e.eventTime = o.path("eventTime").asLong();
        e.ingestTs = o.path("ingestTs").asLong();
        e.severity = Json.text(o, "severity", "unknown");
        e.type = Json.text(o, "type");
        e.service = Json.text(o, "service");
        e.payload = o.get("payload");
        return e;
    }
}
