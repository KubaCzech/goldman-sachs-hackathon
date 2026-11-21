package gs.alerting.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gs.alerting.api.Auth.ApiError;
import gs.alerting.api.Auth.Caller;
import gs.alerting.core.Config;
import gs.alerting.core.Event;
import gs.alerting.core.Json;
import gs.alerting.core.Runbooks;
import gs.alerting.core.Template;
import gs.alerting.util.Db;
import gs.alerting.util.Env;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Runbook CRUD, matching, rendering, and step execution with approval and audit (SPEC 5.9, 5.10). */
public final class RunbookRoutes {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    /** Mock-only: lets sample runbook URLs such as http://counterparty-service/health reach the built-in mock target. */
    private static final Map<String, String> HOST_MAP = new HashMap<>();

    static {
        for (String pair : Env.get("ACTION_HOST_MAP", "counterparty-service=localhost:8080").split(",")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) HOST_MAP.put(kv[0].trim(), kv[1].trim());
        }
    }

    /** Swap point for semantic search: the default ranks with Postgres full-text search (SPEC D11). */
    public interface RunbookSearch { List<ObjectNode> suggest(ObjectNode alert, String teamId, int limit); }

    /** Swap point for an LLM (self-hosted or hosted) that turns prose into structured steps. No-op by default. */
    public interface LlmProvider { ObjectNode extractSteps(String prose); }

    static RunbookSearch search = RunbookRoutes::fullTextSuggest;
    static LlmProvider llm = prose -> null;

    private RunbookRoutes() {}

    static Event eventOf(ObjectNode alert) {
        Event e = new Event();
        e.sourceId = Json.text(alert, "sourceId");
        e.schemaId = Json.text(alert, "schemaId");
        e.severity = Json.text(alert, "severity", "unknown");
        e.type = Json.text(alert, "type");
        e.service = Json.text(alert, "service");
        e.eventTime = alert.path("lastSeen").asLong();
        e.receivedAt = e.eventTime;
        e.payload = alert.get("lastEvent");
        return e;
    }

    static JsonNode runbookOf(ObjectNode alert) {
        Config.Snapshot cfg = Store.registry.snapshot();
        Config.Rule rule = cfg.rules.get(Json.text(alert, "ruleId", ""));
        JsonNode rb = Runbooks.select(rule, eventOf(alert), cfg);
        if (rb == null && Json.text(alert, "runbookId") != null) rb = cfg.runbooks.get(Json.text(alert, "runbookId"));
        return rb;
    }

    static void register(Javalin app) {
        app.get("/api/runbooks", ctx -> {
            Caller c = ApiMain.caller(ctx);
            ArrayNode out = Json.arr();
            for (ObjectNode r : Store.list("runbook")) if (c.member(Json.text(r, "teamId"))) out.add(r.put("canEdit", c.teamAdmin(Json.text(r, "teamId"))));
            ApiMain.json(ctx, out);
        });
        app.post("/api/runbooks/parse", ctx -> {
            ObjectNode b = ApiMain.body(ctx);
            ApiMain.json(ctx, Runbooks.parse(Json.text(b, "content", ""), Json.text(b, "format")));
        });
        app.post("/api/runbooks", ctx -> ApiMain.json(ctx.status(201), save(ApiMain.caller(ctx), ApiMain.body(ctx), null)));
        app.put("/api/runbooks/{id}", ctx -> ApiMain.json(ctx, save(ApiMain.caller(ctx), ApiMain.body(ctx), ctx.pathParam("id"))));
        app.delete("/api/runbooks/{id}", ctx -> {
            Caller c = ApiMain.caller(ctx);
            ObjectNode r = ApiMain.must("runbook", ctx.pathParam("id"));
            c.requireTeamAdmin(Json.text(r, "teamId"));
            Store.delete("runbook", Json.text(r, "id"));
            Store.audit(c.userId, "RUNBOOK_DELETED", "runbook", Json.text(r, "id"), null, null);
            ctx.status(204);
        });
        app.get("/api/action-types", ctx -> ApiMain.json(ctx, Db.query("SELECT doc FROM action_types ORDER BY type").stream().map(r -> r.get("doc")).toList()));
        app.post("/api/action-types", ctx -> {
            Caller c = ApiMain.caller(ctx);
            c.requirePlatformAdmin();
            ObjectNode b = ApiMain.body(ctx);
            String type = ApiMain.need(b, "type");
            ApiMain.need(b, "urlTemplate");
            Db.exec("INSERT INTO action_types (type, doc) VALUES (?, ?::jsonb) ON CONFLICT (type) DO UPDATE SET doc = EXCLUDED.doc", type, b);
            Store.audit(c.userId, "ACTION_TYPE_SAVED", "action_type", type, null, null);
            ApiMain.json(ctx.status(201), b);
        });

        app.get("/api/alerts/{id}/runbook", ctx -> ApiMain.json(ctx, view(AlertRoutes.alertFor(ctx))));
        app.post("/api/alerts/{id}/runbook/link", ctx -> {
            Caller c = ApiMain.caller(ctx);
            ObjectNode alert = AlertRoutes.alertFor(ctx);
            ObjectNode rule0 = ApiMain.must("rule", Json.text(alert, "ruleId"));
            ApiMain.requireEditRule(c, rule0);
            ObjectNode rb = ApiMain.must("runbook", ApiMain.need(ApiMain.body(ctx), "runbookId"));
            c.requireMember(Json.text(rb, "teamId"));
            ObjectNode rule = ApiMain.must("rule", Json.text(alert, "ruleId"));
            rule.put("runbookId", Json.text(rb, "id")).put("version", rule.path("version").asInt(1) + 1);
            Store.save("rule", rule);
            Store.audit(c.userId, "RUNBOOK_LINKED", "rule", Json.text(rule, "id"), Json.text(alert, "alertId"), Json.obj().put("runbookId", Json.text(rb, "id")));
            ApiMain.json(ctx, view(alert));
        });
        app.post("/api/alerts/{id}/runbook/steps/{index}/execute", ctx -> step(ctx, "execute"));
        app.post("/api/alerts/{id}/runbook/steps/{index}/approve", ctx -> step(ctx, "approve"));
        app.post("/api/alerts/{id}/runbook/steps/{index}/reject", ctx -> step(ctx, "reject"));
    }

    private static ObjectNode save(Caller c, ObjectNode b, String id) {
        ObjectNode existing = id == null ? null : ApiMain.must("runbook", id);
        String team = existing != null ? Json.text(existing, "teamId") : ApiMain.need(b, "teamId");
        c.requireTeamAdmin(team);
        String content = b.has("content") ? Json.text(b, "content", "") : Json.text(existing, "content", "");
        ObjectNode rb = Runbooks.parse(content, Json.text(b, "format"));
        String name = Json.text(b, "name");
        if (name != null && !name.isBlank()) rb.put("name", name);
        else if (existing != null && !b.has("content")) rb.put("name", Json.text(existing, "name"));
        if (b.has("matches") && b.get("matches").isObject()) rb.set("matches", b.get("matches"));
        rb.put("id", existing != null ? id : Store.uniqueId("runbook", Json.text(rb, "name", "runbook"))).put("teamId", team);
        rb.put("version", existing == null ? 1 : existing.path("version").asInt(1) + 1);
        Store.save("runbook", rb);
        Store.audit(c.userId, existing == null ? "RUNBOOK_CREATED" : "RUNBOOK_UPDATED", "runbook", Json.text(rb, "id"), null, null);
        return rb.put("canEdit", true);
    }

    /** Runbook for an alert with placeholders filled, per-step status, and suggestions when nothing is linked. */
    static ObjectNode view(ObjectNode alert) {
        ObjectNode out = Json.obj();
        JsonNode rb = runbookOf(alert);
        ArrayNode steps = out.putArray("steps"), sugg = out.putArray("suggestions");
        if (rb == null) {
            out.putNull("runbook");
            for (ObjectNode s : search.suggest(alert, Json.text(alert, "teamId"), 3)) sugg.add(s);
            return out;
        }
        out.set("runbook", rb);
        Event e = eventOf(alert);
        Map<Integer, ObjectNode> status = new HashMap<>();
        for (ObjectNode r : Db.query("SELECT step_index, status, result, requested_by, approved_by, updated_at FROM runbook_steps WHERE alert_id = ?", Json.text(alert, "alertId")))
            status.put(r.get("step_index").asInt(), r);
        int i = 0;
        for (JsonNode s : rb.path("steps")) {
            List<String> unresolved = new ArrayList<>();
            ObjectNode o = steps.addObject();
            o.put("index", i).put("text", Template.render(Json.text(s, "text", ""), e, unresolved));
            o.put("condition", Json.text(s, "condition"));
            JsonNode a = s.get("action");
            if (a != null && a.isObject()) {
                ObjectNode ra = o.putObject("action");
                ra.put("type", Json.text(a, "type"));
                ObjectNode params = ra.putObject("params");
                a.path("params").fields().forEachRemaining(en -> params.put(en.getKey(), Template.render(Json.text(en.getValue()), e, unresolved)));
            } else o.putNull("action");
            o.put("approval", Json.text(s, "approval", "none"));
            ObjectNode st = status.get(i);
            o.put("status", st == null ? "NOT_RUN" : Json.text(st, "status")).put("result", st == null ? null : Json.text(st, "result"));
            o.put("requestedBy", st == null ? null : Json.text(st, "requested_by")).put("approvedBy", st == null ? null : Json.text(st, "approved_by"));
            ArrayNode u = o.putArray("unresolved");
            unresolved.forEach(u::add);
            i++;
        }
        return out;
    }

    private static void setStep(String alertId, int index, String status, String result, String requestedBy, String approvedBy) {
        Db.exec("INSERT INTO runbook_steps (alert_id, step_index, status, result, requested_by, approved_by, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (alert_id, step_index) DO UPDATE SET status = EXCLUDED.status, result = EXCLUDED.result, "
                + "requested_by = COALESCE(EXCLUDED.requested_by, runbook_steps.requested_by), approved_by = EXCLUDED.approved_by, updated_at = EXCLUDED.updated_at",
                alertId, index, status, result, requestedBy, approvedBy, System.currentTimeMillis());
    }

    private static void step(Context ctx, String verb) {
        Caller c = ApiMain.caller(ctx);
        ObjectNode alert = AlertRoutes.alertFor(ctx);
        String alertId = Json.text(alert, "alertId"), team = Json.text(alert, "teamId");
        int index = Integer.parseInt(ctx.pathParam("index"));
        JsonNode rb = runbookOf(alert);
        if (rb == null || index < 0 || index >= rb.path("steps").size()) throw new ApiError(404, "No such runbook step");
        JsonNode s = rb.get("steps").get(index);
        JsonNode action = s.get("action");
        ObjectNode detail = Json.obj().put("runbookId", Json.text(rb, "id")).put("step", index).put("text", Json.text(s, "text"));
        boolean needsApproval = "required".equals(Json.text(s, "approval"));
        ObjectNode cur = Db.one("SELECT status FROM runbook_steps WHERE alert_id = ? AND step_index = ?", alertId, index);
        boolean pending = cur != null && "PENDING_APPROVAL".equals(Json.text(cur, "status"));

        if (verb.equals("reject")) {
            c.requireTeamAdmin(team);
            if (!pending) throw new IllegalArgumentException("This step is not waiting for approval");
            setStep(alertId, index, "REJECTED", "Rejected by " + c.userId, null, null);
            Store.audit(c.userId, "STEP_REJECTED", "runbook_step", String.valueOf(index), alertId, detail);
        } else if (verb.equals("approve") || !needsApproval || c.teamAdmin(team)) {
            if (verb.equals("approve")) {
                c.requireTeamAdmin(team);
                if (!pending) throw new IllegalArgumentException("This step is not waiting for approval");
            }
            String approvedBy = needsApproval ? c.userId : null;
            if (action == null || !action.isObject()) {
                setStep(alertId, index, "DONE", "Marked done by " + c.userId, c.userId, approvedBy);
                Store.audit(c.userId, "STEP_MARKED_DONE", "runbook_step", String.valueOf(index), alertId, detail);
            } else {
                String result;
                boolean ok;
                try { result = run(action, alert, team); ok = result.startsWith("HTTP 2"); }
                catch (ApiError e) { throw e; }
                catch (Exception e) { result = "Failed: " + e.getMessage(); ok = false; }
                setStep(alertId, index, ok ? "DONE" : "FAILED", result, pending ? null : c.userId, approvedBy);
                Store.audit(c.userId, needsApproval ? "STEP_APPROVED_AND_RUN" : "STEP_RUN", "runbook_step", String.valueOf(index), alertId,
                        detail.put("action", Json.text(action, "type")).put("result", result));
            }
        } else {
            setStep(alertId, index, "PENDING_APPROVAL", "Waiting for approval from a team admin", c.userId, null);
            Store.audit(c.userId, "STEP_APPROVAL_REQUESTED", "runbook_step", String.valueOf(index), alertId, detail);
        }
        ApiMain.json(ctx, view(alert));
    }

    /** Execute one action: built-in http_get / http_post, or a configured action type (HTTP template). */
    static String run(JsonNode action, ObjectNode alert, String teamId) throws Exception {
        String type = Json.text(action, "type", "");
        Event e = eventOf(alert);
        JsonNode params = action.path("params");
        Function<String, JsonNode> resolver = path -> {
            JsonNode p = params.get(path);
            if (p != null && !p.isNull()) return Json.M.getNodeFactory().textNode(Template.render(Json.text(p), e, null));
            return e.resolve(path);
        };
        String method, url, body = null;
        if (type.equals("http_get") || type.equals("http_post")) {
            method = type.equals("http_get") ? "GET" : "POST";
            url = Template.render(Json.text(params, "url", ""), resolver, null);
            if (method.equals("POST")) body = Template.render(Json.text(params, "body", "{}"), resolver, null);
        } else {
            ObjectNode row = Db.one("SELECT doc FROM action_types WHERE type = ?", type);
            if (row == null) throw new ApiError(400, "No action type '" + type + "' is configured. A platform admin can add it under action types.");
            JsonNode at = row.get("doc");
            method = Json.text(at, "method", "POST").toUpperCase();
            url = Template.render(Json.text(at, "urlTemplate", ""), resolver, null);
            body = Template.render(Json.text(at, "bodyTemplate", "{}"), resolver, null);
        }
        if (url.contains("⟦missing")) throw new ApiError(400, "The action URL has a value that is missing from this alert: " + url);
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (host == null) throw new ApiError(400, "The action URL is not valid: " + url);
        Set<String> allowed = new LinkedHashSet<>();
        for (ObjectNode r : Db.query("SELECT host FROM host_allowlist WHERE team_id IS NULL OR team_id = ?", teamId)) allowed.add(Json.text(r, "host"));
        if (!allowed.contains(host)) throw new ApiError(403, "Host '" + host + "' is not on the allowlist. A platform admin must add it first.");
        String mapped = HOST_MAP.get(host);
        if (mapped != null) uri = URI.create(uri.getScheme() + "://" + mapped + (uri.getRawPath() == null ? "" : uri.getRawPath()) + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json");
        HttpRequest req = method.equals("GET") ? rb.GET().build() : rb.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body)).build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        String text = resp.body() == null ? "" : resp.body();
        return "HTTP " + resp.statusCode() + " from " + method + " " + url + (text.isEmpty() ? "" : " — " + (text.length() > 200 ? text.substring(0, 200) + "…" : text));
    }

    /** Full-text ranking of the team's runbooks against the alert's words; score is relative to the best hit. */
    static List<ObjectNode> fullTextSuggest(ObjectNode alert, String teamId, int limit) {
        String text = String.join(" ", Json.text(alert, "title", ""), Json.text(alert, "type", ""), Json.text(alert, "service", ""),
                Json.text(alert, "body", ""), Json.str(alert.path("lastEvent")));
        Set<String> terms = new LinkedHashSet<>();
        for (String w : text.toLowerCase().split("[^a-z0-9]+")) if (w.length() > 2 && !w.matches("\\d+") && terms.size() < 40) terms.add(w);
        if (terms.isEmpty()) return List.of();
        List<ObjectNode> rows = Db.query("SELECT id, doc->>'name' AS name, ts_rank(to_tsvector('english', coalesce(doc->>'name','') || ' ' || coalesce(doc->>'content','')), "
                + "to_tsquery('english', ?)) AS rank FROM config WHERE kind = 'runbook' AND team_id = ? ORDER BY rank DESC LIMIT ?", String.join(" | ", terms), teamId, limit);
        double best = rows.isEmpty() ? 0 : rows.get(0).path("rank").asDouble();
        List<ObjectNode> out = new ArrayList<>();
        for (ObjectNode r : rows) {
            double rank = r.path("rank").asDouble();
            if (rank <= 0) continue;
            out.add(Json.obj().put("runbookId", Json.text(r, "id")).put("name", Json.text(r, "name")).put("score", Math.round(100.0 * rank / best * Math.min(1.0, rank * 10)) / 100.0));
        }
        return out;
    }
}
