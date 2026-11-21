package gs.alerting.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import gs.alerting.core.Json;
import org.postgresql.util.PGobject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Minimal JDBC helper: rows come back as JSON objects keyed by column label. */
public final class Db {
    private static HikariDataSource ds;

    private Db() {}

    public static void init() throws Exception {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(Env.get("DB_URL", "jdbc:postgresql://localhost:5432/alerting"));
        c.setUsername(Env.get("DB_USER", "alerting"));
        c.setPassword(Env.get("DB_PASSWORD", "alerting"));
        c.setMaximumPoolSize(Env.getInt("DB_POOL", 10));
        c.setInitializationFailTimeout(-1);
        ds = new HikariDataSource(c);
        for (int i = 0; ; i++) {
            try (Connection con = ds.getConnection()) { return; }
            catch (Exception e) { if (i > 60) throw e; Thread.sleep(1000); }
        }
    }

    public static boolean ok() {
        try (Connection con = ds.getConnection(); Statement st = con.createStatement()) { st.execute("SELECT 1"); return true; }
        catch (Exception e) { return false; }
    }

    public static void script(String sql) {
        try (Connection con = ds.getConnection(); Statement st = con.createStatement()) { st.execute(sql); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void bind(PreparedStatement ps, Object[] params) throws Exception {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof JsonNode n) ps.setString(i + 1, Json.str(n));
            else ps.setObject(i + 1, p);
        }
    }

    public static List<ObjectNode> query(String sql, Object... params) {
        try (Connection con = ds.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<ObjectNode> out = new ArrayList<>();
                while (rs.next()) {
                    ObjectNode o = Json.obj();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        String k = md.getColumnLabel(i);
                        Object v = rs.getObject(i);
                        if (v == null) o.putNull(k);
                        else if (v instanceof PGobject pg) o.set(k, pg.getValue() == null ? null : Json.parse(pg.getValue()));
                        else if (v instanceof Long l) o.put(k, l);
                        else if (v instanceof Integer n) o.put(k, n);
                        else if (v instanceof Boolean b) o.put(k, b);
                        else if (v instanceof Number n) o.put(k, n.doubleValue());
                        else o.put(k, String.valueOf(v));
                    }
                    out.add(o);
                }
                return out;
            }
        } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static ObjectNode one(String sql, Object... params) {
        List<ObjectNode> r = query(sql, params);
        return r.isEmpty() ? null : r.get(0);
    }

    public static int exec(String sql, Object... params) {
        try (Connection con = ds.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static final String SCHEMA = """
        CREATE TABLE IF NOT EXISTS config (kind text NOT NULL, id text NOT NULL, team_id text, doc jsonb NOT NULL, updated_at bigint, PRIMARY KEY (kind, id));
        CREATE TABLE IF NOT EXISTS alerts (alert_id text PRIMARY KEY, alert_key text, team_id text, rule_id text, source_id text, status text,
            severity text, severity_rank int, type text, service text, title text, count bigint, first_seen bigint, last_seen bigint,
            updated_at bigint, doc jsonb, last_event jsonb);
        CREATE INDEX IF NOT EXISTS alerts_team_seen ON alerts (team_id, last_seen DESC);
        CREATE TABLE IF NOT EXISTS deliveries (notification_id text PRIMARY KEY, alert_id text, user_id text, team_id text, channel text, address text,
            title text, status text, attempt int, latency_ms bigint, error text, created_at bigint, updated_at bigint);
        CREATE INDEX IF NOT EXISTS deliveries_alert ON deliveries (alert_id);
        CREATE TABLE IF NOT EXISTS runbook_steps (alert_id text, step_index int, status text, result text, requested_by text, approved_by text,
            updated_at bigint, PRIMARY KEY (alert_id, step_index));
        CREATE TABLE IF NOT EXISTS audit_log (id bigserial PRIMARY KEY, ts bigint, user_id text, action text, resource_type text, resource_id text,
            alert_id text, detail jsonb);
        CREATE INDEX IF NOT EXISTS audit_alert ON audit_log (alert_id);
        CREATE TABLE IF NOT EXISTS channel_status (channel text PRIMARY KEY, down boolean NOT NULL DEFAULT false);
        CREATE TABLE IF NOT EXISTS poll_status (source_id text PRIMARY KEY, last_poll_at bigint, http_status int, ok boolean, error text,
            took_ms bigint, events_sent bigint NOT NULL DEFAULT 0, last_sent_at bigint);
        CREATE TABLE IF NOT EXISTS action_types (type text PRIMARY KEY, doc jsonb NOT NULL);
        CREATE TABLE IF NOT EXISTS host_allowlist (host text NOT NULL, team_id text);
        CREATE TABLE IF NOT EXISTS saved_filters (id text PRIMARY KEY, owner_user_id text, team_id text, scope text, name text, sql text);
        INSERT INTO channel_status (channel, down) VALUES ('email', false), ('sms', false), ('phone', false) ON CONFLICT DO NOTHING;
        """;
}
