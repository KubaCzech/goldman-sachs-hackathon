package gs.alerting.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure alert state machine and notification fan-out (SPEC 8, AlertState and Fan-out).
 * No Kafka types here so that the logic is unit-testable; the Streams processor is a thin wrapper.
 */
public final class AlertEngine {
    public static final String PUBLIC_URL = System.getenv().getOrDefault("PUBLIC_URL", "http://localhost:3000");

    public static final class Result {
        public ObjectNode state;                       // new state to store (never null)
        public ObjectNode alert;                       // alert to emit, or null
        public final List<ObjectNode> notifications = new ArrayList<>();
    }

    private AlertEngine() {}

    public static boolean active(JsonNode alert) {
        return alert != null && alert.isObject() && !"RESOLVED".equals(Json.text(alert, "status"));
    }

    public static Result onMatch(ObjectNode state, Config.Rule rule, Event e, boolean resolve, Config.Snapshot cfg, long ts, long wallClock) {
        Result r = new Result();
        r.state = state == null ? Json.obj() : state;
        JsonNode cur = r.state.get("alert");
        boolean isActive = active(cur);

        if (resolve) {
            if (!isActive) return r;
            ObjectNode a = (ObjectNode) cur;
            a.put("status", "RESOLVED").put("resolution", "auto").put("resolvedBy", "system").put("resolvedAt", ts).put("updatedAt", wallClock);
            r.state.put("windowCount", 0);
            r.alert = a;
            return r;
        }

        int windowCount = 1;
        if (rule.windowCount > 0) {
            long ws = r.state.path("windowStart").asLong(0);
            windowCount = r.state.path("windowCount").asInt(0);
            if (ws == 0 || ts - ws >= rule.windowSeconds * 1000L || ts < ws) { ws = ts; windowCount = 0; }
            windowCount++;
            r.state.put("windowStart", ws).put("windowCount", windowCount);
            if (!isActive && windowCount < rule.windowCount) return r;
        }

        String severity = rule.severityOverride != null ? Severity.normalise(rule.severityOverride) : e.severity;
        if (!isActive) {
            ObjectNode a = Json.obj();
            String key = rule.alertKey(e);
            a.put("alertId", "a-" + Json.sha1Hex(key + "|" + ts + "|" + e.eventId).substring(0, 16));
            a.put("alertKey", key).put("ruleId", rule.id).put("ruleName", rule.name).put("teamId", rule.teamId)
                    .put("sourceId", e.sourceId).put("schemaId", e.schemaId).put("status", "OPEN");
            a.put("count", rule.windowCount > 0 ? windowCount : 1).put("firstSeen", ts);
            ObjectNode gv = a.putObject("groupValues");
            for (String g : rule.groupBy) gv.put(g, Json.text(e.resolve(g)));
            a.putNull("ackBy").putNull("ackAt").putNull("resolvedBy").putNull("resolvedAt").putNull("resolution").putNull("note");
            a.put("ingestTs", e.ingestTs);
            present(a, rule, e, severity, ts, wallClock, cfg);
            r.state.set("alert", a);
            r.state.put("lastNotifiedAt", ts).put("notifySeq", 0);
            r.alert = a;
            fanOut(r, a, rule, e, cfg, 0, wallClock);
            return r;
        }

        ObjectNode a = (ObjectNode) cur;
        a.put("count", a.path("count").asLong(0) + 1);
        a.put("ingestTs", e.ingestTs);
        if (Severity.rank(severity) < Severity.rank(Json.text(a, "severity"))) severity = Json.text(a, "severity");
        present(a, rule, e, severity, ts, wallClock, cfg);
        r.alert = a;
        if (rule.renotifyMinutes > 0 && ts - r.state.path("lastNotifiedAt").asLong(0) >= rule.renotifyMinutes * 60_000L) {
            int seq = r.state.path("notifySeq").asInt(0) + 1;
            r.state.put("lastNotifiedAt", ts).put("notifySeq", seq);
            fanOut(r, a, rule, e, cfg, seq, wallClock);
        }
        return r;
    }

    public static Result onCommand(ObjectNode state, JsonNode cmd, long wallClock) {
        Result r = new Result();
        r.state = state == null ? Json.obj() : state;
        JsonNode cur = r.state.get("alert");
        if (cur == null || !cur.isObject() || !Json.text(cur, "alertId", "").equals(Json.text(cmd, "alertId"))) return r;
        ObjectNode a = (ObjectNode) cur;
        String status = Json.text(a, "status"), action = Json.text(cmd, "action", "");
        String user = Json.text(cmd, "userId");
        long ts = cmd.path("ts").asLong(wallClock);
        if (action.equals("ACK") && "OPEN".equals(status)) {
            a.put("status", "ACKNOWLEDGED").put("ackBy", user).put("ackAt", ts);
        } else if (action.equals("RESOLVE") && !"RESOLVED".equals(status)) {
            a.put("status", "RESOLVED").put("resolution", "manual").put("resolvedBy", user).put("resolvedAt", ts);
            r.state.put("windowCount", 0);
        } else return r;
        String note = Json.text(cmd, "note");
        if (note != null && !note.isBlank()) a.put("note", note);
        a.put("updatedAt", wallClock);
        r.alert = a;
        return r;
    }

    private static void present(ObjectNode a, Config.Rule rule, Event e, String severity, long ts, long wallClock, Config.Snapshot cfg) {
        a.put("severity", severity).put("color", rule.color != null ? rule.color : Severity.defaultColor(severity));
        a.put("title", Template.render(rule.titleTpl, e, null)).put("body", Template.render(rule.bodyTpl, e, null));
        ObjectNode f = a.putObject("fields");
        for (String p : rule.fields) f.put(p, Json.text(e.resolve(p)));
        a.put("lastSeen", ts).put("type", e.type).put("service", e.service);
        a.set("lastEvent", e.payload);
        JsonNode rb = Runbooks.select(rule, e, cfg);
        a.put("runbookId", rb == null ? null : Json.text(rb, "id"));
        a.put("ruleName", rule.name).put("updatedAt", wallClock);
    }

    /** Rule targets → team policy → user channel preferences → quiet hours. */
    static void fanOut(Result r, ObjectNode alert, Config.Rule rule, Event e, Config.Snapshot cfg, int seq, long wallClock) {
        Set<String> userIds = new LinkedHashSet<>();
        for (JsonNode t : rule.targets) {
            String type = Json.text(t, "type", ""), id = Json.text(t, "id", "");
            switch (type) {
                case "user" -> userIds.add(id);
                case "oncall" -> { String oc = Json.text(cfg.teams.get(id), "onCallUserId"); if (oc != null) userIds.add(oc); }
                case "team" -> {
                    JsonNode team = cfg.teams.get(id);
                    if (team == null) break;
                    String oc = Json.text(team, "onCallUserId");
                    if ("oncall".equals(Json.text(team, "notify")) && oc != null) userIds.add(oc);
                    else for (JsonNode m : team.path("members")) userIds.add(Json.text(m, "userId"));
                }
                default -> { }
            }
        }
        if (userIds.isEmpty()) return;
        String severity = Json.text(alert, "severity", "unknown");
        String alertId = Json.text(alert, "alertId");
        String link = PUBLIC_URL + "/alerts/" + alertId;
        String runbookText = null;
        for (String uid : userIds) {
            JsonNode u = cfg.users.get(uid);
            if (u == null) continue;
            List<String> channels = !rule.channels.isEmpty() ? rule.channels : Json.strings(u.path("channels").get(severity));
            if (channels.isEmpty()) continue;
            JsonNode q = u.path("quietHours");
            if (q.path("enabled").asBoolean(false) && inQuietHours(q, wallClock)) {
                String fb = Json.text(q, "fallbackChannel");
                if (fb == null || fb.isBlank() || fb.equals("none")) continue;
                channels = List.of(fb);
            }
            Map<String, Boolean> seen = new LinkedHashMap<>();
            for (String ch : channels) {
                if (ch.equals("ui") || seen.put(ch, true) != null) continue;   // the UI feed is implicit
                String address = ch.equals("email") ? Json.text(u, "email") : Json.text(u, "phone");
                if (address == null || address.isBlank()) continue;
                ObjectNode n = Json.obj();
                n.put("notificationId", Json.sha1Hex(alertId + "|" + uid + "|" + ch + "|" + seq));
                n.put("alertId", alertId).put("alertKey", Json.text(alert, "alertKey")).put("ruleId", rule.id).put("teamId", rule.teamId);
                n.put("userId", uid).put("channel", ch).put("address", address).put("severity", severity);
                n.put("title", Json.text(alert, "title")).put("body", Json.text(alert, "body"));
                n.set("fields", alert.get("fields"));
                if (ch.equals("email")) {
                    if (runbookText == null) runbookText = Runbooks.renderText(Runbooks.select(rule, e, cfg), e);
                    n.put("runbookText", runbookText);
                }
                n.put("link", link).put("ingestTs", e.ingestTs).put("createdAt", wallClock).put("attempt", 0).put("notBefore", 0);
                r.notifications.add(n);
            }
        }
    }

    static boolean inQuietHours(JsonNode q, long wallClock) {
        try {
            ZoneId zone = ZoneId.of(Json.text(q, "timezone", "UTC"));
            LocalTime now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(wallClock), zone).toLocalTime();
            LocalTime start = LocalTime.parse(Json.text(q, "start", "20:00")), end = LocalTime.parse(Json.text(q, "end", "08:00"));
            if (start.equals(end)) return false;
            return start.isBefore(end) ? (!now.isBefore(start) && now.isBefore(end)) : (!now.isBefore(start) || now.isBefore(end));
        } catch (Exception ex) {
            return false;
        }
    }
}
