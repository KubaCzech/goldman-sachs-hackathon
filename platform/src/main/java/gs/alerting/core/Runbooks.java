package gs.alerting.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runbook import (YAML / Markdown / anything else), selection and rendering (SPEC 5.9). */
public final class Runbooks {
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:\\d+[.)]|[-*+])\\s+(.*)$");
    private static final Pattern HEADING = Pattern.compile("^\\s*#+\\s+(.*)$");
    private static final Set<String> STEP_META = Set.of("check", "if", "action", "approval", "text", "description", "name");

    private Runbooks() {}

    /** Parse any text into the canonical runbook shape. Never fails: unknown formats become one text step. */
    public static ObjectNode parse(String content, String formatHint) {
        String c = content == null ? "" : content;
        ObjectNode rb = null;
        if (formatHint == null || formatHint.isBlank() || formatHint.equals("yaml") || formatHint.equals("auto")) rb = tryYaml(c);
        if (rb == null && !"text".equals(formatHint)) rb = tryMarkdown(c);
        if (rb == null) {
            rb = Json.obj();
            rb.put("format", "text");
            String first = c.strip().lines().findFirst().orElse("Runbook");
            rb.put("name", first.length() > 60 ? first.substring(0, 60) + "…" : first);
            rb.putObject("matches");
            ObjectNode s = rb.putArray("steps").addObject();
            s.put("text", c.strip());
            s.putNull("action");
            s.put("approval", "none");
        }
        rb.put("content", c);
        return rb;
    }

    @SuppressWarnings("unchecked")
    private static ObjectNode tryYaml(String c) {
        Object o;
        try { o = new Yaml(new SafeConstructor(new LoaderOptions())).load(c); } catch (Exception e) { return null; }
        if (!(o instanceof Map<?, ?> m) || !(m.get("steps") instanceof List<?> steps)) return null;
        ObjectNode rb = Json.obj();
        rb.put("format", "yaml");
        Object name = m.containsKey("runbook") ? m.get("runbook") : m.containsKey("name") ? m.get("name") : m.get("title");
        rb.put("name", name == null ? "runbook" : String.valueOf(name));
        ObjectNode matches = rb.putObject("matches");
        if (m.get("matches") instanceof Map<?, ?> mm) mm.forEach((k, v) -> matches.put(String.valueOf(k), String.valueOf(v)));
        ArrayNode out = rb.putArray("steps");
        for (Object so : steps) {
            ObjectNode s = out.addObject();
            if (!(so instanceof Map<?, ?>)) {
                s.put("text", String.valueOf(so));
                s.putNull("action");
                s.put("approval", "none");
                continue;
            }
            Map<String, Object> sm = (Map<String, Object>) so;
            String action = sm.get("action") == null ? null : String.valueOf(sm.get("action"));
            String cond = sm.get("if") == null ? null : String.valueOf(sm.get("if"));
            Object text = sm.get("check") != null ? sm.get("check") : sm.get("text") != null ? sm.get("text")
                    : sm.get("description") != null ? sm.get("description") : sm.get("name");
            ObjectNode params = Json.obj();
            sm.forEach((k, v) -> { if (!STEP_META.contains(k)) params.set(k, Json.M.valueToTree(v)); });
            String t = text != null ? String.valueOf(text) : action != null ? humanise(action, params) : (cond != null ? cond : "Step");
            s.put("text", t);
            if (cond != null) s.put("condition", cond);
            if (action != null) {
                ObjectNode a = s.putObject("action");
                a.put("type", action);
                a.set("params", params);
            } else s.putNull("action");
            Object appr = sm.get("approval");
            s.put("approval", appr != null ? String.valueOf(appr) : (action == null || action.equals("http_get")) ? "none" : "required");
        }
        return rb;
    }

    private static String humanise(String action, ObjectNode params) {
        StringBuilder sb = new StringBuilder(action.replace('_', ' '));
        sb.setCharAt(0, Character.toUpperCase(sb.charAt(0)));
        params.fields().forEachRemaining(en -> sb.append(' ').append(Json.text(en.getValue())));
        return sb.toString();
    }

    private static ObjectNode tryMarkdown(String c) {
        List<String> items = new ArrayList<>();
        String heading = null;
        for (String line : c.split("\\R")) {
            Matcher h = HEADING.matcher(line);
            if (h.matches()) { if (heading == null) heading = h.group(1).trim(); continue; }
            Matcher m = LIST_ITEM.matcher(line);
            if (m.matches()) items.add(m.group(1).trim());
            else if (!items.isEmpty() && !line.isBlank() && line.startsWith(" ")) items.set(items.size() - 1, items.get(items.size() - 1) + " " + line.trim());
        }
        if (items.size() < 2 && heading == null) return null;
        if (items.isEmpty()) return null;
        ObjectNode rb = Json.obj();
        rb.put("format", "markdown");
        rb.put("name", heading == null ? "Runbook" : heading);
        rb.putObject("matches");
        ArrayNode out = rb.putArray("steps");
        for (String it : items) {
            ObjectNode s = out.addObject();
            s.put("text", it);
            s.putNull("action");
            s.put("approval", it.toLowerCase().contains("needs approval") ? "required" : "none");
        }
        return rb;
    }

    /** Explicit rule link first; otherwise the team's runbook whose {@code matches} all hold, most keys wins. */
    public static JsonNode select(Config.Rule rule, Event e, Config.Snapshot cfg) {
        if (rule != null && rule.runbookId != null) {
            JsonNode rb = cfg.runbooks.get(rule.runbookId);
            if (rb != null) return rb;
        }
        JsonNode best = null;
        int bestKeys = 0;
        for (JsonNode rb : cfg.runbooks.values()) {
            if (rule != null && rule.teamId != null && !rule.teamId.equals(Json.text(rb, "teamId"))) continue;
            JsonNode m = rb.get("matches");
            if (m == null || !m.isObject() || m.size() == 0 || m.size() <= bestKeys) continue;
            boolean ok = true;
            for (Iterator<Map.Entry<String, JsonNode>> it = m.fields(); it.hasNext() && ok; ) {
                Map.Entry<String, JsonNode> en = it.next();
                String v = Json.text(e.resolve(en.getKey()));
                ok = v != null && v.equals(en.getValue().asText());
            }
            if (ok) { best = rb; bestKeys = m.size(); }
        }
        return best;
    }

    /** Plain-text rendering of a runbook for email bodies. */
    public static String renderText(JsonNode rb, Event e) {
        if (rb == null) return "";
        StringBuilder sb = new StringBuilder("Runbook: ").append(Json.text(rb, "name", "")).append('\n');
        int i = 1;
        for (JsonNode s : rb.path("steps")) {
            sb.append(i++).append(". ");
            String cond = Json.text(s, "condition");
            if (cond != null) sb.append("[if ").append(cond).append("] ");
            sb.append(Template.render(Json.text(s, "text", ""), e, null));
            JsonNode a = s.get("action");
            if (a != null && a.isObject()) {
                sb.append("  (action: ").append(Json.text(a, "type"));
                if ("required".equals(Json.text(s, "approval"))) sb.append(", needs approval");
                sb.append(')');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
