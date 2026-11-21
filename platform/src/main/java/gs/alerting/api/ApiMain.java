package gs.alerting.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gs.alerting.api.Auth.ApiError;
import gs.alerting.api.Auth.Caller;
import gs.alerting.core.AlertEngine;
import gs.alerting.core.Config;
import gs.alerting.core.Event;
import gs.alerting.core.Filter;
import gs.alerting.core.Json;
import gs.alerting.core.Normaliser;
import gs.alerting.core.SqlParser;
import gs.alerting.core.Template;
import gs.alerting.stream.AlertTopology;
import gs.alerting.util.Db;
import gs.alerting.util.Env;
import gs.alerting.util.Health;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** ROLE=api: REST, SSE, webhook ingest, config publisher and alert projector (SPEC 10). */
public final class ApiMain {
    private static final Logger log = LoggerFactory.getLogger(ApiMain.class);
    static KafkaProducer<String, byte[]> ingestProducer;
    static final AtomicLong INGESTED = new AtomicLong(), INGEST_REJECTED = new AtomicLong();
    static volatile boolean ready = false;

    public static void run() throws Exception {
        Db.init();
        Db.script(Db.SCHEMA);
        try (Admin admin = awaitKafka()) {
            Env.ensureTopic(admin, AlertTopology.CFG_TOPIC, 1, Map.of("cleanup.policy", "compact"));
            for (String t : List.of(AlertTopology.MATCHES_TOPIC, AlertTopology.COMMANDS_TOPIC, AlertTopology.NOTIFICATIONS_TOPIC,
                    "notifications.retry", "notifications.dlq", AlertTopology.DLQ_TOPIC)) Env.ensureTopic(admin, t, Env.PARTITIONS, Map.of());
            Env.ensureTopic(admin, AlertTopology.ALERTS_TOPIC, Env.PARTITIONS, Map.of("cleanup.policy", "compact,delete", "retention.ms", String.valueOf(14L * 24 * 3600 * 1000)));
            Store.init();
            Seed.runIfEmpty();
            for (ObjectNode s : Store.list("source")) Env.ensureTopic(admin, "ingest." + Json.text(s, "id"), Env.PARTITIONS, Map.of());
        }
        Store.recomputeRules();
        Store.republishAll();
        ingestProducer = new KafkaProducer<>(Env.producer(true));
        startConfigFollower();
        AlertRoutes.startProjector();
        AlertRoutes.startBroadcaster();

        Javalin app = Javalin.create(c -> {
            c.showJavalinBanner = false;
            c.http.maxRequestSize = 5_000_000L;
        });
        Health.register(app, () -> ready && Db.ok(), () -> {
            Map<String, Number> m = new LinkedHashMap<>();
            m.put("alerting_ingest_accepted_total", INGESTED.get());
            m.put("alerting_ingest_rejected_total", INGEST_REJECTED.get());
            m.put("alerting_sse_clients", AlertRoutes.clients.size());
            m.put("alerting_projected_alert_updates_total", AlertRoutes.PROJECTED.get());
            return m;
        });
        app.exception(ApiError.class, (e, ctx) -> json(ctx.status(e.status), Json.obj().put("error", e.getMessage())));
        app.exception(SqlParser.ParseException.class, (e, ctx) -> json(ctx.status(400), Json.obj().put("error", e.getMessage()).put("position", e.position)));
        app.exception(IllegalArgumentException.class, (e, ctx) -> json(ctx.status(400), Json.obj().put("error", e.getMessage())));
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled error on {} {}", ctx.method(), ctx.path(), e);
            json(ctx.status(500), Json.obj().put("error", "Internal error: " + e.getMessage()));
        });
        app.before("/api/*", ctx -> {
            if (ctx.path().startsWith("/api/auth/") || ctx.method().name().equals("OPTIONS")) return;
            String h = ctx.header("Authorization");
            String token = h != null && h.startsWith("Bearer ") ? h.substring(7) : ctx.queryParam("token");
            if (token == null || token.isBlank()) throw new ApiError(401, "Please sign in");
            ctx.attribute("caller", Auth.load(Auth.verify(token)));
        });

        authRoutes(app);
        teamRoutes(app);
        sourceRoutes(app);
        schemaRoutes(app);
        ruleRoutes(app);
        filterRoutes(app);
        ingestRoutes(app);
        AlertRoutes.register(app);
        RunbookRoutes.register(app);

        app.start(Env.getInt("PORT", 8080));
        ready = true;
        log.info("API started");
    }

    private static Admin awaitKafka() throws Exception {
        Admin admin = Admin.create(Env.base());
        for (int i = 0; ; i++) {
            try {
                if (admin.describeCluster().nodes().get().size() >= Math.min(Env.replication(), 3)) return admin;
            } catch (Exception e) { if (i > 120) throw e; }
            Thread.sleep(1000);
        }
    }

    /** Keeps the in-memory registry in step with the cfg topic so every API replica sees every change. */
    private static void startConfigFollower() {
        Thread t = new Thread(() -> {
            try (KafkaConsumer<String, String> c = new KafkaConsumer<>(Env.consumer("api-cfg-" + UUID.randomUUID(), true))) {
                List<TopicPartition> tps = List.of(new TopicPartition(AlertTopology.CFG_TOPIC, 0));
                c.assign(tps);
                c.seekToBeginning(tps);
                while (true) for (ConsumerRecord<String, String> r : c.poll(Duration.ofSeconds(1))) Store.registry.apply(r.key(), r.value());
            } catch (Exception e) { log.error("Config follower stopped", e); }
        }, "cfg-follower");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- helpers

    static void json(Context ctx, JsonNode n) { ctx.contentType("application/json").result(Json.str(n)); }
    static void json(Context ctx, List<? extends JsonNode> list) { ArrayNode a = Json.arr(); list.forEach(a::add); json(ctx, a); }
    static Caller caller(Context ctx) { return ctx.attribute("caller"); }

    static ObjectNode body(Context ctx) {
        String b = ctx.body();
        if (b == null || b.isBlank()) return Json.obj();
        JsonNode n = Json.parse(b);
        if (!n.isObject()) throw new IllegalArgumentException("Expected a JSON object");
        return (ObjectNode) n;
    }

    static ObjectNode must(String kind, String id) {
        ObjectNode d = Store.get(kind, id);
        if (d == null) throw new ApiError(404, "No " + kind + " with id '" + id + "'");
        return d;
    }

    static String need(JsonNode o, String field) {
        String v = Json.text(o, field);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("'" + field + "' is required");
        return v;
    }

    // ---------------------------------------------------------------- auth, users, teams

    private static void authRoutes(Javalin app) {
        app.get("/api/auth/users", ctx -> {
            List<ObjectNode> teams = Store.list("team");
            ArrayNode out = Json.arr();
            for (ObjectNode u : Store.list("user")) {
                ObjectNode o = out.addObject();
                String id = Json.text(u, "id");
                o.put("id", id).put("username", Json.text(u, "username")).put("displayName", Json.text(u, "displayName"))
                        .put("platformAdmin", u.path("platformAdmin").asBoolean(false));
                ArrayNode ts = o.putArray("teams");
                for (ObjectNode t : teams) for (JsonNode m : t.path("members"))
                    if (id.equals(Json.text(m, "userId"))) ts.addObject().put("teamId", Json.text(t, "id")).put("teamName", Json.text(t, "name")).put("role", Json.text(m, "role"));
            }
            json(ctx, out);
        });
        app.post("/api/auth/login", ctx -> {
            String username = need(body(ctx), "username");
            for (ObjectNode u : Store.list("user")) {
                if (username.equals(Json.text(u, "username")) || username.equals(Json.text(u, "id"))) {
                    Caller c = Auth.load(Json.text(u, "id"));
                    ObjectNode o = Json.obj().put("token", Auth.issue(c.userId));
                    o.set("user", c.toJson());
                    json(ctx, o);
                    return;
                }
            }
            throw new ApiError(401, "Unknown user '" + username + "'");
        });
        app.get("/api/me", ctx -> json(ctx, caller(ctx).toJson()));
        app.get("/api/me/preferences", ctx -> {
            ObjectNode u = caller(ctx).user, o = Json.obj();
            o.put("email", Json.text(u, "email")).put("phone", Json.text(u, "phone"));
            String theme = Json.text(u, "theme");
            if (theme != null) o.put("theme", theme);
            o.set("channels", u.path("channels"));
            o.set("quietHours", u.path("quietHours"));
            json(ctx, o);
        });
        app.put("/api/me/preferences", ctx -> {
            Caller c = caller(ctx);
            ObjectNode b = body(ctx), u = c.user;
            if (b.has("theme")) {
                String theme = Json.text(b, "theme");
                if (!"light".equals(theme) && !"dark".equals(theme)) throw new IllegalArgumentException("theme must be 'light' or 'dark'");
                u.put("theme", theme);
            }
            for (String k : List.of("email", "phone", "channels", "quietHours")) if (b.has(k)) u.set(k, b.get(k));
            Store.save("user", u);
            Store.audit(c.userId, "PREFERENCES_UPDATED", "user", c.userId, null, null);
            json(ctx, u);
        });
        app.get("/api/users", ctx -> {
            ArrayNode out = Json.arr();
            for (ObjectNode u : Store.list("user")) out.addObject().put("id", Json.text(u, "id")).put("username", Json.text(u, "username")).put("displayName", Json.text(u, "displayName"));
            json(ctx, out);
        });
    }

    private static void teamRoutes(Javalin app) {
        app.get("/api/teams", ctx -> {
            Caller c = caller(ctx);
            Map<String, String> names = new LinkedHashMap<>();
            for (ObjectNode u : Store.list("user")) names.put(Json.text(u, "id"), Json.text(u, "displayName"));
            ArrayNode out = Json.arr();
            for (ObjectNode t : Store.list("team")) {
                String id = Json.text(t, "id");
                if (c.member(id)) {
                    for (JsonNode m : t.path("members")) ((ObjectNode) m).put("displayName", names.get(Json.text(m, "userId")));
                    t.put("role", c.roles.get(id)).put("canEdit", c.teamAdmin(id));
                    out.add(t);
                } else out.addObject().put("id", id).put("name", Json.text(t, "name")).put("canEdit", false);
            }
            json(ctx, out);
        });
        app.put("/api/teams/{id}/policy", ctx -> {
            Caller c = caller(ctx);
            ObjectNode t = must("team", ctx.pathParam("id")), b = body(ctx);
            c.requireTeamAdmin(Json.text(t, "id"));
            String notify = Json.text(b, "notify", "all");
            if (!notify.equals("all") && !notify.equals("oncall")) throw new IllegalArgumentException("notify must be 'all' or 'oncall'");
            t.put("notify", notify);
            if (b.has("onCallUserId")) t.set("onCallUserId", b.get("onCallUserId"));
            Store.save("team", t);
            Store.audit(c.userId, "TEAM_POLICY_UPDATED", "team", Json.text(t, "id"), null, b);
            json(ctx, t);
        });
    }

    // ---------------------------------------------------------------- sources and schemas

    private static ObjectNode sourceView(ObjectNode s, Caller c) {
        ObjectNode o = s.deepCopy();
        String id = Json.text(s, "id");
        boolean own = c.member(Json.text(s, "teamId"));
        o.put("topic", "ingest." + id).put("ingestUrl", "/ingest/" + id).put("canEdit", c.teamAdmin(Json.text(s, "teamId"))).put("owned", own);
        if (!own) o.remove("token");                         // a grant lets a team read events, not write them
        if (!o.has("grantedTeamIds")) o.putArray("grantedTeamIds");
        return o;
    }

    private static void sourceRoutes(Javalin app) {
        app.get("/api/sources", ctx -> {
            Caller c = caller(ctx);
            ArrayNode out = Json.arr();
            for (ObjectNode s : Store.list("source")) {
                boolean granted = Json.strings(s.get("grantedTeamIds")).stream().anyMatch(c.roles::containsKey);
                if (c.member(Json.text(s, "teamId")) || granted) out.add(sourceView(s, c));
            }
            json(ctx, out);
        });
        app.post("/api/sources", ctx -> {
            Caller c = caller(ctx);
            ObjectNode b = body(ctx);
            String team = need(b, "teamId"), name = need(b, "name");
            c.requireTeamAdmin(team);
            String type = Json.text(b, "type", "webhook");
            if (!type.equals("webhook") && !type.equals("kafka")) throw new IllegalArgumentException("type must be 'webhook' or 'kafka'");
            ObjectNode s = Json.obj();
            String id = Store.uniqueId("source", name);
            s.put("id", id).put("teamId", team).put("name", name).put("type", type).put("defaultSchemaId", Json.text(b, "defaultSchemaId"));
            byte[] rnd = new byte[18];
            new SecureRandom().nextBytes(rnd);
            s.put("token", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(rnd));
            s.putArray("grantedTeamIds");
            try (Admin admin = Admin.create(Env.base())) { Env.ensureTopic(admin, "ingest." + id, Env.PARTITIONS, Map.of()); }
            Store.save("source", s);
            Store.recomputeRules();
            Store.audit(c.userId, "SOURCE_CREATED", "source", id, null, null);
            json(ctx.status(201), sourceView(s, c));
        });
        app.put("/api/sources/{id}", ctx -> {
            Caller c = caller(ctx);
            ObjectNode s = must("source", ctx.pathParam("id")), b = body(ctx);
            c.requireTeamAdmin(Json.text(s, "teamId"));
            for (String k : List.of("name", "defaultSchemaId")) if (b.has(k)) s.set(k, b.get(k));
            Store.save("source", s);
            json(ctx, sourceView(s, c));
        });
        app.delete("/api/sources/{id}", ctx -> {
            Caller c = caller(ctx);
            ObjectNode s = must("source", ctx.pathParam("id"));
            c.requireTeamAdmin(Json.text(s, "teamId"));
            Store.delete("source", Json.text(s, "id"));
            Store.recomputeRules();
            Store.audit(c.userId, "SOURCE_DELETED", "source", Json.text(s, "id"), null, null);
            ctx.status(204);
        });
        app.post("/api/sources/{id}/grants", ctx -> {
            Caller c = caller(ctx);
            ObjectNode s = must("source", ctx.pathParam("id"));
            c.requireTeamAdmin(Json.text(s, "teamId"));
            String team = need(body(ctx), "teamId");
            must("team", team);
            ArrayNode g = s.has("grantedTeamIds") ? (ArrayNode) s.get("grantedTeamIds") : s.putArray("grantedTeamIds");
            if (!Json.strings(g).contains(team)) g.add(team);
            Store.save("source", s);
            Store.recomputeRules();
            Store.audit(c.userId, "SOURCE_GRANTED", "source", Json.text(s, "id"), null, Json.obj().put("teamId", team));
            json(ctx, sourceView(s, c));
        });
        app.delete("/api/sources/{id}/grants/{teamId}", ctx -> {
            Caller c = caller(ctx);
            ObjectNode s = must("source", ctx.pathParam("id"));
            c.requireTeamAdmin(Json.text(s, "teamId"));
            ArrayNode g = s.putArray("grantedTeamIds");
            for (String t : Json.strings(must("source", ctx.pathParam("id")).get("grantedTeamIds"))) if (!t.equals(ctx.pathParam("teamId"))) g.add(t);
            Store.save("source", s);
            Store.recomputeRules();
            Store.audit(c.userId, "SOURCE_GRANT_REVOKED", "source", Json.text(s, "id"), null, Json.obj().put("teamId", ctx.pathParam("teamId")));
            json(ctx, sourceView(s, c));
        });
    }

    private static void schemaRoutes(Javalin app) {
        // Schemas describe structure only, so every signed-in user may read them; the owning team's admins edit them.
        app.get("/api/schemas", ctx -> {
            Caller c = caller(ctx);
            ArrayNode out = Json.arr();
            for (ObjectNode s : Store.list("schema")) { s.put("canEdit", c.teamAdmin(Json.text(s, "teamId"))); out.add(s); }
            json(ctx, out);
        });
        app.post("/api/schemas/infer", ctx -> {
            JsonNode sample = body(ctx).get("sample");
            if (sample != null && sample.isTextual()) sample = Json.parse(sample.asText());
            if (sample == null || !sample.isObject()) throw new IllegalArgumentException("Paste one JSON object as the sample");
            json(ctx, Normaliser.infer(sample));
        });
        app.post("/api/schemas", ctx -> json(ctx.status(201), saveSchema(caller(ctx), body(ctx), null)));
        app.put("/api/schemas/{id}", ctx -> json(ctx, saveSchema(caller(ctx), body(ctx), ctx.pathParam("id"))));
        app.delete("/api/schemas/{id}", ctx -> {
            Caller c = caller(ctx);
            ObjectNode s = must("schema", ctx.pathParam("id"));
            c.requireTeamAdmin(Json.text(s, "teamId"));
            Store.delete("schema", Json.text(s, "id"));
            ctx.status(204);
        });
        app.get("/api/fields", ctx -> {
            ArrayNode out = Json.arr();
            for (String[] f : new String[][]{{"severity", "string", "error"}, {"type", "string", ""}, {"service", "string", ""}, {"source", "string", ""},
                    {"schema", "string", ""}, {"eventTime", "number", ""}, {"status", "string", "OPEN"}, {"count", "number", ""}})
                out.addObject().put("path", f[0]).put("type", f[1]).put("example", f[2]).put("schemaId", "canonical");
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (ObjectNode s : Store.list("schema")) for (JsonNode f : s.path("fields")) {
                String p = Json.text(f, "path");
                if (p == null || !seen.add(p)) continue;
                ObjectNode o = ((ObjectNode) f).deepCopy();
                o.put("schemaId", Json.text(s, "id"));
                out.add(o);
            }
            json(ctx, out);
        });
    }

    private static ObjectNode saveSchema(Caller c, ObjectNode b, String id) {
        ObjectNode existing = id == null ? null : must("schema", id);
        String team = existing != null ? Json.text(existing, "teamId") : need(b, "teamId");
        c.requireTeamAdmin(team);
        ObjectNode s = existing != null ? existing : Json.obj();
        if (existing == null) s.put("id", Store.uniqueId("schema", need(b, "name"))).put("teamId", team);
        if (b.has("name")) s.set("name", b.get("name"));
        s.put("priority", b.path("priority").asInt(s.path("priority").asInt(0)));
        String matchSql = Json.text(b, "matchSql");
        if (matchSql != null) {
            if (matchSql.isBlank()) { s.putNull("match"); s.put("matchSql", ""); }
            else { ObjectNode ast = SqlParser.parse(matchSql); s.set("match", ast); s.put("matchSql", Filter.print(ast)); }
        } else if (b.has("match")) {
            JsonNode m = b.get("match");
            if (m == null || m.isNull()) { s.putNull("match"); s.put("matchSql", ""); }
            else { Filter.compile(m); s.set("match", m); s.put("matchSql", Filter.print(m)); }
        }
        if (b.has("mapping")) s.set("mapping", b.get("mapping"));
        if (b.has("fields")) s.set("fields", b.get("fields"));
        if (b.has("sample")) s.set("sample", b.get("sample"));
        Store.save("schema", s);
        Store.audit(c.userId, existing == null ? "SCHEMA_CREATED" : "SCHEMA_UPDATED", "schema", Json.text(s, "id"), null, null);
        s.put("canEdit", true);
        return s;
    }

    // ---------------------------------------------------------------- rules and filters

    /** Validate and complete a rule document from a request body. */
    static ObjectNode buildRule(ObjectNode b, ObjectNode existing) {
        ObjectNode r = existing != null ? existing : Json.obj();
        for (String k : List.of("name", "enabled", "sourceIds", "groupBy", "window", "severity", "targets", "channels", "presentation", "runbookId", "renotifyMinutes"))
            if (b.has(k)) r.set(k, b.get(k));
        if (!r.has("enabled")) r.put("enabled", true);
        String sql = Json.text(b, "filterSql");
        JsonNode ast = b.get("filter");
        if (sql != null && (ast == null || ast.isNull() || b.path("filterMode").asText("").equals("sql"))) ast = SqlParser.parse(sql);
        if (ast != null && !ast.isNull()) {
            Filter.compile(ast);
            r.set("filter", ast);
            r.put("filterSql", Filter.print(ast));
        } else if (!r.has("filter")) {
            r.set("filter", Filter.alwaysTrue());
            r.put("filterSql", "");
        }
        if (b.has("resolveWhenSql") || b.has("resolveWhen")) {
            String rs = Json.text(b, "resolveWhenSql");
            // An explicit AST wins (the editor sends it and may echo back a stale resolveWhenSql); SQL text is for API and file users.
            JsonNode ra = b.has("resolveWhen") ? b.get("resolveWhen") : (rs == null || rs.isBlank() ? null : SqlParser.parse(rs));
            if (ra == null || ra.isNull()) { r.putNull("resolveWhen"); r.put("resolveWhenSql", ""); }
            else { Filter.compile(ra); r.set("resolveWhen", ra); r.put("resolveWhenSql", Filter.print(ra)); }
        }
        JsonNode w = r.get("window");
        if (w != null && w.isObject() && (w.path("count").asInt() < 2 || w.path("seconds").asInt() < 1)) r.putNull("window");
        return r;
    }

    private static void ruleRoutes(Javalin app) {
        app.get("/api/rules", ctx -> {
            Caller c = caller(ctx);
            ArrayNode out = Json.arr();
            for (ObjectNode r : Store.list("rule")) {
                String team = Json.text(r, "teamId");
                if (!c.member(team)) continue;
                r.put("canEdit", c.teamAdmin(team)).put("teamName", c.teamNames.getOrDefault(team, team));
                out.add(r);
            }
            json(ctx, out);
        });
        app.post("/api/rules", ctx -> {
            Caller c = caller(ctx);
            ObjectNode b = body(ctx);
            String team = need(b, "teamId");
            c.requireTeamAdmin(team);
            ObjectNode r = buildRule(b, null);
            r.put("id", Store.uniqueId("rule", need(b, "name"))).put("teamId", team).put("version", 1);
            r.set("allowedSources", Store.allowedSources(r, Store.list("source")));
            Store.save("rule", r);
            Store.audit(c.userId, "RULE_CREATED", "rule", Json.text(r, "id"), null, null);
            json(ctx.status(201), r.put("canEdit", true));
        });
        app.put("/api/rules/{id}", ctx -> {
            Caller c = caller(ctx);
            ObjectNode existing = must("rule", ctx.pathParam("id"));
            c.requireTeamAdmin(Json.text(existing, "teamId"));
            ObjectNode r = buildRule(body(ctx), existing);
            r.put("version", existing.path("version").asInt(1) + 1);
            r.set("allowedSources", Store.allowedSources(r, Store.list("source")));
            Store.save("rule", r);
            Store.audit(c.userId, "RULE_UPDATED", "rule", Json.text(r, "id"), null, null);
            json(ctx, r.put("canEdit", true));
        });
        app.delete("/api/rules/{id}", ctx -> {
            Caller c = caller(ctx);
            ObjectNode r = must("rule", ctx.pathParam("id"));
            c.requireTeamAdmin(Json.text(r, "teamId"));
            Store.delete("rule", Json.text(r, "id"));
            Store.audit(c.userId, "RULE_DELETED", "rule", Json.text(r, "id"), null, null);
            ctx.status(204);
        });
        app.post("/api/rules/preview", ctx -> {
            ObjectNode b = body(ctx);
            ObjectNode rb = b.has("rule") && b.get("rule").isObject() ? (ObjectNode) b.get("rule").deepCopy() : Json.obj();
            ObjectNode doc = buildRule(rb, null);
            doc.put("id", Json.text(rb, "id", "preview")).put("teamId", Json.text(rb, "teamId", ""));
            JsonNode sample = b.get("sample");
            if (sample != null && sample.isTextual()) sample = Json.parse(sample.asText());
            if (sample == null || !sample.isObject()) throw new IllegalArgumentException("Provide a sample event (one JSON object)");
            Config.Snapshot cfg = Store.registry.snapshot();
            String sourceId = Json.text(b, "sourceId");
            if (sourceId == null || sourceId.isBlank()) { List<String> s = Json.strings(doc.get("sourceIds")); sourceId = s.isEmpty() ? "preview" : s.get(0); }
            Config.Rule rule = Config.Rule.compile(doc);
            Event e = Normaliser.normalise(sourceId, sample, 0, System.currentTimeMillis(), cfg);
            List<String> unresolved = new ArrayList<>();
            ObjectNode out = Json.obj();
            out.put("matches", rule.filter.test(e));
            out.put("resolves", rule.resolveWhen != null && rule.resolveWhen.test(e));
            String sev = rule.severityOverride != null ? gs.alerting.core.Severity.normalise(rule.severityOverride) : e.severity;
            out.put("severity", sev).put("color", rule.color != null ? rule.color : gs.alerting.core.Severity.defaultColor(sev));
            out.put("schemaId", e.schemaId).put("type", e.type).put("service", e.service);
            out.put("title", Template.render(rule.titleTpl, e, unresolved)).put("body", Template.render(rule.bodyTpl, e, unresolved));
            ObjectNode f = out.putObject("fields");
            for (String p : rule.fields) f.put(p, Json.text(e.resolve(p)));
            ArrayNode u = out.putArray("unresolved");
            unresolved.forEach(u::add);
            out.put("alertKey", rule.alertKey(e)).put("filterSql", Json.text(doc, "filterSql"));
            json(ctx, out);
        });
    }

    private static void filterRoutes(Javalin app) {
        app.post("/api/filters/parse", ctx -> {
            ObjectNode ast = SqlParser.parse(Json.text(body(ctx), "sql", ""));
            ObjectNode o = Json.obj();
            o.set("ast", ast);
            o.put("guiCompatible", Filter.guiCompatible(ast)).put("sql", Filter.print(ast));
            json(ctx, o);
        });
        app.post("/api/filters/print", ctx -> {
            JsonNode ast = body(ctx).get("ast");
            Filter.compile(ast);
            json(ctx, Json.obj().put("sql", Filter.print(ast)).put("guiCompatible", Filter.guiCompatible(ast)));
        });
        app.get("/api/saved-filters", ctx -> {
            Caller c = caller(ctx);
            List<ObjectNode> out = new ArrayList<>();
            for (ObjectNode f : Db.query("SELECT id, owner_user_id AS \"ownerUserId\", team_id AS \"teamId\", scope, name, sql FROM saved_filters ORDER BY name"))
                if ("team".equals(Json.text(f, "scope")) ? c.member(Json.text(f, "teamId")) : c.userId.equals(Json.text(f, "ownerUserId"))) {
                    f.put("canDelete", "team".equals(Json.text(f, "scope")) ? c.teamAdmin(Json.text(f, "teamId")) : true);
                    out.add(f);
                }
            json(ctx, out);
        });
        app.post("/api/saved-filters", ctx -> {
            Caller c = caller(ctx);
            ObjectNode b = body(ctx);
            String scope = Json.text(b, "scope", "personal"), sql = Json.text(b, "sql", "");
            SqlParser.parse(sql);
            String team = null;
            if (scope.equals("team")) { team = need(b, "teamId"); c.requireTeamAdmin(team); }
            String id = UUID.randomUUID().toString();
            Db.exec("INSERT INTO saved_filters (id, owner_user_id, team_id, scope, name, sql) VALUES (?, ?, ?, ?, ?, ?)", id, c.userId, team, scope.equals("team") ? "team" : "personal", need(b, "name"), sql);
            json(ctx.status(201), Json.obj().put("id", id).put("name", Json.text(b, "name")).put("scope", scope).put("teamId", team).put("sql", sql));
        });
        app.delete("/api/saved-filters/{id}", ctx -> {
            Caller c = caller(ctx);
            ObjectNode f = Db.one("SELECT owner_user_id, team_id, scope FROM saved_filters WHERE id = ?", ctx.pathParam("id"));
            if (f == null) throw new ApiError(404, "No such saved filter");
            if ("team".equals(Json.text(f, "scope"))) c.requireTeamAdmin(Json.text(f, "team_id"));
            else if (!c.userId.equals(Json.text(f, "owner_user_id"))) throw new ApiError(403, "This filter belongs to someone else");
            Db.exec("DELETE FROM saved_filters WHERE id = ?", ctx.pathParam("id"));
            ctx.status(204);
        });
    }

    // ---------------------------------------------------------------- ingest gateway and mock targets

    private static void ingestRoutes(Javalin app) {
        app.post("/ingest/{sourceId}", ctx -> {
            String sourceId = ctx.pathParam("sourceId");
            JsonNode src = Store.registry.snapshot().sources.get(sourceId);
            String token = ctx.header("X-Source-Token");
            if (token == null) token = ctx.queryParam("token");
            if (src == null || token == null || !token.equals(Json.text(src, "token"))) {
                INGEST_REJECTED.incrementAndGet();
                throw new ApiError(src == null ? 404 : 401, src == null ? "Unknown source" : "Missing or wrong X-Source-Token");
            }
            byte[] bodyBytes = ctx.bodyAsBytes();
            if (bodyBytes.length == 0) throw new IllegalArgumentException("Empty body");
            ProducerRecord<String, byte[]> rec = new ProducerRecord<>("ingest." + sourceId, null, bodyBytes);
            rec.headers().add("ingest-ts", String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.US_ASCII));
            ingestProducer.send(rec);
            INGESTED.incrementAndGet();
            json(ctx.status(202), Json.obj().put("accepted", 1));
        });
        // Stand-in for the systems that runbook actions call (retry a workflow, restart a service, open a ticket...).
        io.javalin.http.Handler mock = ctx -> {
            log.info("MOCK TARGET {} {} {}", ctx.method(), ctx.path(), ctx.body());
            if (ctx.path().contains("/fail")) json(ctx.status(500), Json.obj().put("ok", false).put("path", ctx.path()));
            else json(ctx, Json.obj().put("ok", true).put("path", ctx.path()).put("status", "UP"));
        };
        app.get("/mock/target/*", mock);
        app.post("/mock/target/*", mock);
        app.get("/health", mock);
    }

    static String publicUrl() { return AlertEngine.PUBLIC_URL; }
}
