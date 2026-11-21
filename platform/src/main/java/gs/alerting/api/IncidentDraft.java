package gs.alerting.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gs.alerting.core.Json;
import gs.alerting.util.Db;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Save what was done as a runbook": turns the audit trail of a handled alert into a draft runbook (YAML) that the
 * team reviews and saves. Runbook steps that were run keep their original templates ({{workflowId}} stays a
 * placeholder); notes written while handling the alert become text steps, with values from the alert's event replaced
 * by placeholders so that the draft fits the next incident, not only this one.
 */
final class IncidentDraft {
    private IncidentDraft() {}

    static ObjectNode build(ObjectNode alert) {
        String alertId = Json.text(alert, "alertId");
        JsonNode rb = RunbookRoutes.runbookOf(alert);
        JsonNode templates = rb == null ? Json.arr() : rb.path("steps");
        Map<String, String> placeholders = placeholders(alert.path("lastEvent"), alert);

        List<String> lines = new ArrayList<>();
        ArrayNode used = Json.arr();
        int fromRunbook = 0, fromNotes = 0;
        for (ObjectNode a : Db.query("SELECT ts, user_id, action, detail FROM audit_log WHERE alert_id = ? ORDER BY ts, id", alertId)) {
            String action = Json.text(a, "action", "");
            JsonNode d = a.path("detail");
            switch (action) {
                case "STEP_RUN", "STEP_APPROVED_AND_RUN", "STEP_MARKED_DONE" -> {
                    int i = d.path("step").asInt(-1);
                    if (i < 0 || i >= templates.size()) continue;
                    lines.add(stepYaml(templates.get(i), action.equals("STEP_APPROVED_AND_RUN"), null));
                    used.addObject().put("what", "step " + (i + 1) + " of " + Json.text(rb, "name", "the runbook")).put("by", Json.text(a, "user_id")).put("audit", action);
                    fromRunbook++;
                }
                case "STEP_REJECTED" -> {
                    int i = d.path("step").asInt(-1);
                    if (i < 0 || i >= templates.size()) continue;
                    lines.add(stepYaml(templates.get(i), true, "was rejected during incident " + alertId + "; keep only if it applies"));
                    used.addObject().put("what", "rejected step " + (i + 1)).put("by", Json.text(a, "user_id")).put("audit", action);
                }
                case "ALERT_ACKNOWLEDGED", "ALERT_RESOLVED" -> {
                    String note = Json.text(d, "note");
                    if (note == null || note.isBlank()) continue;
                    lines.add("  - text: " + q(templatise(note, placeholders)));
                    used.addObject().put("what", (action.equals("ALERT_RESOLVED") ? "resolve" : "acknowledge") + " note").put("by", Json.text(a, "user_id")).put("audit", action);
                    fromNotes++;
                }
                default -> { }
            }
        }
        String note = Json.text(alert, "note");   // a note sent with ack/resolve is also kept on the alert itself
        if (fromNotes == 0 && note != null && !note.isBlank()) { lines.add("  - text: " + q(templatise(note, placeholders))); fromNotes++; }

        String what = Json.text(alert, "type", Json.text(alert, "ruleName", "alert"));
        String svc = Json.text(alert, "service");
        String name = what + (svc != null ? " on " + svc : "") + " (from incident " + alertId + ")";
        StringBuilder y = new StringBuilder();
        y.append("# Draft written from what was done for alert ").append(alertId).append(". Review it before saving.\n");
        y.append("runbook: ").append(q(name)).append('\n');
        y.append("matches:\n");
        if (alert.hasNonNull("type")) y.append("  type: ").append(q(Json.text(alert, "type"))).append('\n');
        if (svc != null) y.append("  service: ").append(q(svc)).append('\n');
        y.append("steps:\n");
        if (lines.isEmpty()) y.append("  - text: \"Describe the first thing to check\"\n");
        lines.forEach(l -> y.append(l).append('\n'));

        ObjectNode out = Json.obj();
        out.put("name", name).put("teamId", Json.text(alert, "teamId")).put("content", y.toString());
        out.put("stepsFromRunbook", fromRunbook).put("stepsFromNotes", fromNotes);
        out.set("basedOn", used);
        return out;
    }

    private static String stepYaml(JsonNode s, boolean needsApproval, String comment) {
        StringBuilder b = new StringBuilder();
        if (comment != null) b.append("  # ").append(comment.replace('\n', ' ')).append('\n');
        JsonNode action = s.get("action");
        String text = Json.text(s, "text", "Step");
        if (action == null || !action.isObject()) return b.append("  - text: ").append(q(text)).toString();
        b.append("  - check: ").append(q(text)).append('\n');
        b.append("    action: ").append(q(Json.text(action, "type"))).append('\n');
        action.path("params").fields().forEachRemaining(en -> b.append("    ").append(en.getKey()).append(": ").append(q(Json.text(en.getValue()))).append('\n'));
        String cond = Json.text(s, "condition");
        if (cond != null) b.append("    if: ").append(q(cond)).append('\n');
        b.append("    approval: ").append("required".equals(Json.text(s, "approval")) || needsApproval ? "required" : "none");
        return b.toString();
    }

    /** Event values worth turning back into placeholders: longer values first so "WF-1-retry" wins over "WF-1". */
    private static Map<String, String> placeholders(JsonNode event, ObjectNode alert) {
        Map<String, String> byValue = new LinkedHashMap<>();
        leaves(event, "", byValue, 0);
        if (alert.hasNonNull("service")) byValue.putIfAbsent(Json.text(alert, "service"), "service");
        List<Map.Entry<String, String>> sorted = new ArrayList<>(byValue.entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> -e.getKey().length()));
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : sorted) out.put(e.getKey(), e.getValue());
        return out;
    }

    private static void leaves(JsonNode n, String prefix, Map<String, String> out, int depth) {
        if (n == null || depth > 6) return;
        if (n.isObject()) n.fields().forEachRemaining(en -> leaves(en.getValue(), prefix.isEmpty() ? en.getKey() : prefix + "." + en.getKey(), out, depth + 1));
        else if (n.isValueNode() && !prefix.isEmpty()) {
            String v = n.asText();
            // Short or generic values (true, prod, 3) would be replaced in unrelated words; only distinctive ones.
            if (v.length() >= 4 && !v.matches("(?i)true|false|null|prod|test|info|warn|error") && !v.contains("\n")) out.putIfAbsent(v, prefix);
        }
    }

    static String templatise(String text, Map<String, String> placeholders) {
        String t = text;
        for (Map.Entry<String, String> e : placeholders.entrySet()) t = t.replace(e.getKey(), "{{" + e.getValue() + "}}");
        return t;
    }

    /** YAML double-quoted scalar (JSON string syntax is valid YAML). */
    private static String q(String s) { return Json.str(Json.M.getNodeFactory().textNode(s == null ? "" : s)); }
}
