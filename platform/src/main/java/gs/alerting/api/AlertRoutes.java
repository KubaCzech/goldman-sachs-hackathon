package gs.alerting.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gs.alerting.api.Auth.ApiError;
import gs.alerting.api.Auth.Caller;
import gs.alerting.core.FilterSql;
import gs.alerting.core.Json;
import gs.alerting.core.Severity;
import gs.alerting.core.SqlParser;
import gs.alerting.stream.AlertTopology;
import gs.alerting.util.Db;
import gs.alerting.util.Env;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Alert read model (projector), queries, live feed (SSE) and ack/resolve commands (SPEC 10.2, 10.3). */
public final class AlertRoutes {
    private static final Logger log = LoggerFactory.getLogger(AlertRoutes.class);
    static final Map<SseClient, Caller> clients = new ConcurrentHashMap<>();
    static final Map<SseClient, String> clientTeam = new ConcurrentHashMap<>();
    static final AtomicLong PROJECTED = new AtomicLong();

    private AlertRoutes() {}

    static ObjectNode alert(String id) {
        ObjectNode r = Db.one("SELECT doc FROM alerts WHERE alert_id = ?", id);
        if (r == null) throw new ApiError(404, "No alert with id '" + id + "' (it may still be on its way)");
        return (ObjectNode) r.get("doc");
    }

    /** Load an alert and check the caller belongs to its team. */
    static ObjectNode alertFor(Context ctx) {
        ObjectNode a = alert(ctx.pathParam("id"));
        ApiMain.caller(ctx).requireMember(Json.text(a, "teamId"));
        return a;
    }

    static void register(Javalin app) {
        // Registered first so that "stream" is not taken for an alert id.
        app.sse("/api/alerts/stream", client -> {
            Caller c = client.ctx().attribute("caller");
            if (c == null) { client.close(); return; }
            String team = client.ctx().queryParam("teamId");
            client.keepAlive();
            clients.put(client, c);
            if (team != null && !team.isBlank()) clientTeam.put(client, team);
            client.onClose(() -> { clients.remove(client); clientTeam.remove(client); });
            client.sendComment("connected");
        });
        app.get("/api/alerts", ctx -> {
            Caller c = ApiMain.caller(ctx);
            String teamId = ctx.queryParam("teamId");
            List<String> teams;
            if (teamId != null && !teamId.isBlank()) { c.requireMember(teamId); teams = List.of(teamId); }
            else teams = c.teamIds();
            if (teams.isEmpty()) { ApiMain.json(ctx, Json.arr()); return; }
            List<Object> params = new ArrayList<>(teams);
            StringBuilder sql = new StringBuilder("SELECT doc FROM alerts WHERE team_id IN (")
                    .append(teams.stream().map(t -> "?").collect(Collectors.joining(","))).append(")");
            String status = ctx.queryParam("status");
            if (status != null && !status.isBlank() && !status.equalsIgnoreCase("all")) {
                if (status.equalsIgnoreCase("active")) sql.append(" AND status <> 'RESOLVED'");
                else { sql.append(" AND status = ?"); params.add(status.toUpperCase()); }
            }
            String before = ctx.queryParam("before");
            if (before != null && !before.isBlank()) { sql.append(" AND last_seen < ?"); params.add(Long.parseLong(before)); }
            String filter = ctx.queryParam("filter");
            if (filter != null && !filter.isBlank()) sql.append(" AND COALESCE(").append(FilterSql.compile(SqlParser.parse(filter), params)).append(", FALSE)");
            int limit = Math.min(500, Math.max(1, Integer.parseInt(ctx.queryParamAsClass("limit", String.class).getOrDefault("100"))));
            sql.append(" ORDER BY last_seen DESC LIMIT ").append(limit);
            ApiMain.json(ctx, Db.query(sql.toString(), params.toArray()).stream().map(r -> r.get("doc")).collect(Collectors.toList()));
        });
        app.get("/api/alerts/{id}", ctx -> ApiMain.json(ctx, alertFor(ctx)));
        app.post("/api/alerts/{id}/ack", ctx -> command(ctx, "ACK"));
        app.post("/api/alerts/{id}/resolve", ctx -> command(ctx, "RESOLVE"));
        app.get("/api/alerts/{id}/deliveries", ctx -> {
            alertFor(ctx);
            ApiMain.json(ctx, Db.query("SELECT notification_id AS \"notificationId\", user_id AS \"userId\", channel, address, title, status, attempt, "
                    + "latency_ms AS \"latencyMs\", error, created_at AS \"createdAt\", updated_at AS \"updatedAt\" FROM deliveries WHERE alert_id = ? ORDER BY created_at", ctx.pathParam("id")));
        });
        app.get("/api/alerts/{id}/audit", ctx -> {
            alertFor(ctx);
            ApiMain.json(ctx, Db.query("SELECT id, ts, user_id AS \"userId\", action, resource_type AS \"resourceType\", resource_id AS \"resourceId\", detail "
                    + "FROM audit_log WHERE alert_id = ? ORDER BY ts, id", ctx.pathParam("id")));
        });
        app.get("/api/admin/channels", ctx -> ApiMain.json(ctx, Db.query("SELECT channel, down FROM channel_status ORDER BY channel")));
        app.put("/api/admin/channels/{channel}", ctx -> {
            Caller c = ApiMain.caller(ctx);
            c.requirePlatformAdmin();
            boolean down = ApiMain.body(ctx).path("down").asBoolean(false);
            Db.exec("INSERT INTO channel_status (channel, down) VALUES (?, ?) ON CONFLICT (channel) DO UPDATE SET down = EXCLUDED.down", ctx.pathParam("channel"), down);
            Store.audit(c.userId, down ? "CHANNEL_MARKED_DOWN" : "CHANNEL_MARKED_UP", "channel", ctx.pathParam("channel"), null, null);
            ApiMain.json(ctx, Json.obj().put("channel", ctx.pathParam("channel")).put("down", down));
        });
    }

    private static void command(Context ctx, String action) throws Exception {
        Caller c = ApiMain.caller(ctx);
        ObjectNode a = alertFor(ctx);
        String note = Json.text(ApiMain.body(ctx), "note");
        ObjectNode cmd = Json.obj().put("kind", "COMMAND").put("alertId", Json.text(a, "alertId")).put("action", action)
                .put("userId", c.userId).put("note", note).put("ts", System.currentTimeMillis());
        Store.producer.send(new ProducerRecord<>(AlertTopology.COMMANDS_TOPIC, Json.text(a, "alertKey"), Json.str(cmd))).get();
        Store.audit(c.userId, action.equals("ACK") ? "ALERT_ACKNOWLEDGED" : "ALERT_RESOLVED", "alert", Json.text(a, "alertId"), Json.text(a, "alertId"),
                note == null ? null : Json.obj().put("note", note));
        ApiMain.json(ctx.status(202), Json.obj().put("ok", true));
    }

    /** Last record per alert id within one poll batch: 500 updates to one alert become one write. */
    private static Map<String, ObjectNode> coalesce(ConsumerRecords<String, String> recs) {
        Map<String, ObjectNode> last = new LinkedHashMap<>();
        for (ConsumerRecord<String, String> r : recs) {
            if (r.value() == null) continue;
            try { last.put(r.key(), (ObjectNode) Json.M.readTree(r.value())); } catch (Exception ignore) { }
        }
        return last;
    }

    static void startProjector() {
        Thread t = new Thread(() -> {
            while (true) {
                try (KafkaConsumer<String, String> c = new KafkaConsumer<>(Env.consumer("alert-projector", true))) {
                    c.subscribe(List.of(AlertTopology.ALERTS_TOPIC));
                    while (true) {
                        ConsumerRecords<String, String> recs = c.poll(Duration.ofMillis(200));
                        if (recs.isEmpty()) continue;
                        PROJECTED.addAndGet(recs.count());
                        for (ObjectNode a : coalesce(recs).values()) upsert(a);
                        c.commitSync();
                    }
                } catch (Exception e) {
                    log.error("Projector failed; restarting", e);
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
                }
            }
        }, "alert-projector");
        t.setDaemon(true);
        t.start();
    }

    private static void upsert(ObjectNode a) {
        String id = Json.text(a, "alertId"), status = Json.text(a, "status");
        ObjectNode prev = Db.one("SELECT status, updated_at FROM alerts WHERE alert_id = ?", id);
        // Out-of-order protection: partitions are per alert key, but replays after a restart may repeat old updates.
        if (prev != null && prev.path("updated_at").asLong() > a.path("updatedAt").asLong()) return;
        Db.exec("INSERT INTO alerts (alert_id, alert_key, team_id, rule_id, source_id, status, severity, severity_rank, type, service, title, count, "
                + "first_seen, last_seen, updated_at, doc, last_event) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb) "
                + "ON CONFLICT (alert_id) DO UPDATE SET status = EXCLUDED.status, severity = EXCLUDED.severity, severity_rank = EXCLUDED.severity_rank, "
                + "type = EXCLUDED.type, service = EXCLUDED.service, title = EXCLUDED.title, count = EXCLUDED.count, last_seen = EXCLUDED.last_seen, "
                + "updated_at = EXCLUDED.updated_at, doc = EXCLUDED.doc, last_event = EXCLUDED.last_event",
                id, Json.text(a, "alertKey"), Json.text(a, "teamId"), Json.text(a, "ruleId"), Json.text(a, "sourceId"), status, Json.text(a, "severity"),
                Severity.rank(Json.text(a, "severity")), Json.text(a, "type"), Json.text(a, "service"), Json.text(a, "title"), a.path("count").asLong(),
                a.path("firstSeen").asLong(), a.path("lastSeen").asLong(), a.path("updatedAt").asLong(), a, a.path("lastEvent"));
        if (prev == null) Store.audit("system", "ALERT_OPENED", "alert", id, id, Json.obj().put("rule", Json.text(a, "ruleName")));
        else if ("RESOLVED".equals(status) && !"RESOLVED".equals(Json.text(prev, "status")) && "auto".equals(Json.text(a, "resolution")))
            Store.audit("system", "ALERT_AUTO_RESOLVED", "alert", id, id, null);
    }

    static void startBroadcaster() {
        Thread t = new Thread(() -> {
            while (true) {
                try (KafkaConsumer<String, String> c = new KafkaConsumer<>(Env.consumer("api-sse-" + UUID.randomUUID(), false))) {
                    c.subscribe(List.of(AlertTopology.ALERTS_TOPIC));
                    while (true) {
                        ConsumerRecords<String, String> recs = c.poll(Duration.ofMillis(200));
                        if (recs.isEmpty() || clients.isEmpty()) continue;
                        for (ObjectNode a : coalesce(recs).values()) {
                            String team = Json.text(a, "teamId"), data = Json.str(a);
                            for (Map.Entry<SseClient, Caller> en : clients.entrySet()) {
                                if (!en.getValue().member(team)) continue;
                                String only = clientTeam.get(en.getKey());
                                if (only != null && !only.equals(team)) continue;
                                try { en.getKey().sendEvent("alert", data); } catch (Exception ex) { clients.remove(en.getKey()); }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Broadcaster failed; restarting", e);
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
                }
            }
        }, "sse-broadcaster");
        t.setDaemon(true);
        t.start();
    }
}
