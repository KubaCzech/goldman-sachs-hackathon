package gs.alerting.api;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gs.alerting.core.Filter;
import gs.alerting.core.Json;
import gs.alerting.core.Normaliser;
import gs.alerting.core.Runbooks;
import gs.alerting.core.SqlParser;
import gs.alerting.util.Db;

/** Demo data from the appendices of the intention document (SPEC 11). Runs once, on an empty database. */
final class Seed {
    static final String WORKFLOW = "{\"type\":\"WORKFLOW_FAILED\",\"workflowId\":\"WF-20261023-00042\",\"workflowName\":\"order-settlement\",\"step\":\"validate-counterparty\",\"severity\":\"ERROR\",\"environment\":\"prod\",\"timestamp\":\"2026-10-23T09:14:02.331Z\",\"error\":{\"code\":\"TIMEOUT\",\"message\":\"Counterparty service did not respond in 5000 ms\"}}";
    static final String METRIC = "{\"alertname\":\"HighErrorRate\",\"labels\":{\"service\":\"payments-api\",\"region\":\"eu-west\",\"severity\":\"critical\"},\"annotations\":{\"summary\":\"Error rate 12% over the last 5 minutes\"},\"startsAt\":\"2026-10-23T09:13:00Z\",\"status\":\"firing\"}";
    static final String TRADE = "{\"meta\":{\"schema\":\"trade.rejected.v2\",\"producer\":\"trade-gateway\",\"ts\":1792833245000},\"payload\":{\"tradeId\":\"T-998877\",\"desk\":\"rates\",\"notional\":25000000,\"currency\":\"EUR\",\"reason\":\"LIMIT_BREACH\"}}";
    static final String LOG = "{\"service\":\"pricing-engine-2\",\"level\":\"WARN\",\"instrument\":\"EURUSD\",\"latencyMs\":1240,\"message\":\"Pricing latency above 1s\",\"time\":\"2026-10-23T09:15:00Z\"}";

    static final String RB_YAML = """
            runbook: settlement-timeout
            matches:
              type: WORKFLOW_FAILED
              "error.code": TIMEOUT
            steps:
              - check: "Is counterparty-service healthy?"
                action: http_get
                url: "http://counterparty-service/health"
              - action: retry_workflow
                workflowId: "{{workflowId}}"
                approval: none
              - if: "retry fails twice"
                action: escalate
                team: counterparty-support
                approval: required
            """;
    static final String RB_MD = """
            # HighErrorRate on payments-api
            1. Open the payments dashboard and check if errors are in one region only.
            2. If one region: fail traffic over to the other region (needs approval from the team lead).
            3. If all regions: check the last deployment and roll it back.
            4. Post an update in the #payments-incidents channel every 15 minutes.
            """;
    static final String RB_TEXT = "When pricing latency goes over a second, it's usually the cache. Restart the pricing-engine instance named in the alert, wait 2 minutes, and if latency is still high, call the pricing on-call person.";

    private Seed() {}

    static void runIfEmpty() {
        if (Db.one("SELECT 1 AS x FROM config LIMIT 1") != null) return;

        user("u-admin", "admin", "Alex (platform admin)", true, null);
        user("u-sam", "sam", "Sam (Settlements admin, on-call)", false, null);
        user("u-sara", "sara", "Sara (Settlements)", false, null);
        user("u-petra", "petra", "Petra (Pricing admin)", false, null);
        user("u-ravi", "ravi", "Ravi (Rates admin)", false, null);
        ObjectNode quiet = Json.obj().put("enabled", true).put("start", "20:00").put("end", "08:00").put("timezone", "Europe/London").put("fallbackChannel", "email");
        user("u-anna", "anna", "Anna (Payments admin)", false, quiet);
        user("u-omar", "omar", "Omar (Payments)", false, null);

        team("settlements", "Settlements", "oncall", "u-sam", "u-sam:admin", "u-sara:member");
        team("pricing", "Pricing", "all", "u-petra", "u-petra:admin");
        team("rates", "Rates", "all", "u-ravi", "u-ravi:admin");
        team("payments", "Payments", "all", "u-anna", "u-anna:admin", "u-omar:member");

        schema("workflow-failed", "settlements", "Workflow events", "type LIKE 'WORKFLOW_%'", WORKFLOW, null);
        schema("alertmanager", "payments", "Metric alerts (Alertmanager style)", "alertname IS NOT NULL", METRIC, null);
        schema("trade-rejected", "rates", "Trade rejected v2", "meta.schema LIKE 'trade.rejected.%'", TRADE, "warning");
        schema("pricing-log", "pricing", "Pricing engine log line", "service LIKE 'pricing-engine-%'", LOG, null);

        source("settlement-bus", "settlements", "Settlement workflow bus", "kafka", "workflow-failed");
        source("metrics-webhook", "payments", "Metrics webhook", "webhook", "alertmanager");
        source("trade-gateway", "rates", "Trade gateway events", "kafka", "trade-rejected");
        source("pricing-logs", "pricing", "Pricing engine logs", "webhook", "pricing-log");
        // An HTTP polling source: the service fetches the status page every 10 s (no webhook needed).
        ObjectNode status = Json.obj().put("id", "pricing-status").put("teamId", "pricing").put("name", "Pricing engine status page")
                .put("type", "poll").putNull("defaultSchemaId").put("token", "demo-pricing-status").put("format", "auto");
        status.putArray("grantedTeamIds");
        status.putObject("poll").put("url", "http://api:8080/mock/status/pricing-engine").put("intervalSeconds", 10).put("onlyWhenChanged", true);
        Store.save("source", status);

        runbook("settlement-timeout", "settlements", RB_YAML);
        runbook("higherrorrate-payments", "payments", RB_MD);
        runbook("pricing-latency", "pricing", RB_TEXT);

        ObjectNode r = rule("settlements-prod-failures", "settlements", "Prod order-settlement failures",
                "type = 'WORKFLOW_FAILED' AND workflowName = 'order-settlement' AND environment = 'prod'", "settlement-bus");
        r.set("resolveWhen", SqlParser.parse("type = 'WORKFLOW_SUCCEEDED' AND workflowName = 'order-settlement'"));
        r.put("resolveWhenSql", Filter.print(r.get("resolveWhen")));
        r.putArray("groupBy").add("workflowName");
        r.putArray("targets").addObject().put("type", "oncall").put("id", "settlements");
        r.putArray("channels").add("phone").add("sms");
        present(r, "{{workflowName}} failed at {{step}}", "{{error.code}}: {{error.message}} (workflow {{workflowId}})", "#d32f2f", "step", "error.message");
        r.put("runbookId", "settlement-timeout");
        Store.save("rule", r);

        r = rule("pricing-status-down", "pricing", "Pricing engine status page not UP",
                "status != 'UP' OR type = 'POLL_FAILED'", "pricing-status");
        r.set("resolveWhen", SqlParser.parse("status = 'UP' OR type = 'POLL_RECOVERED'"));
        r.put("resolveWhenSql", Filter.print(r.get("resolveWhen")));
        r.putArray("groupBy").add("source");   // one alert per watched page, whether it says DOWN or stops answering
        r.putArray("targets").addObject().put("type", "team").put("id", "pricing");
        r.putArray("channels").add("ui");
        r.put("severity", "critical");
        present(r, "Pricing engine status page is not UP", "Found by polling the status page every 10 s.", null, "status", "httpStatus", "message");
        Store.save("rule", r);

        r = rule("pricing-warnings", "pricing", "Pricing engine warnings per instrument", "service LIKE 'pricing-engine-%' AND severity >= 'warning'", "pricing-logs");
        r.putArray("groupBy").add("instrument");
        r.putArray("targets").addObject().put("type", "team").put("id", "pricing");
        r.putArray("channels").add("ui");
        r.put("renotifyMinutes", 5);
        present(r, "Pricing warnings for {{instrument}}", "{{message}} on {{service}}", null, "service", "latencyMs", "message");
        Store.save("rule", r);

        r = rule("rates-large-rejections", "rates", "Large rejected rates trades",
                "meta.schema LIKE 'trade.rejected.%' AND payload.desk = 'rates' AND payload.notional > 10000000", "trade-gateway");
        r.putArray("groupBy").add("payload.tradeId");
        r.putArray("targets").addObject().put("type", "team").put("id", "rates");
        r.putArray("channels").add("email");
        present(r, "Rejected {{payload.currency}} {{payload.notional}} — {{payload.reason}}", "Trade {{payload.tradeId}} on desk {{payload.desk}}", null, "payload.tradeId", "payload.reason");
        Store.save("rule", r);

        r = rule("anna-payments-critical", "payments", "Anna: anything critical from payments-api", "severity = 'critical' AND labels.service = 'payments-api' AND status != 'resolved'", "metrics-webhook");
        r.set("resolveWhen", SqlParser.parse("labels.service = 'payments-api' AND status = 'resolved'"));
        r.put("resolveWhenSql", Filter.print(r.get("resolveWhen")));
        r.putArray("groupBy").add("alertname").add("labels.region");
        r.putArray("targets").addObject().put("type", "user").put("id", "u-anna");
        r.putNull("channels");
        present(r, "{{alertname}} on {{labels.service}} ({{labels.region}})", "{{annotations.summary}}", null, "labels.region", "annotations.summary");
        r.put("runbookId", "higherrorrate-payments");
        Store.save("rule", r);

        for (String[] a : new String[][]{
                {"retry_workflow", "POST", "http://localhost:8080/mock/target/workflows/{{workflowId}}/retry", "{\"workflowId\":\"{{workflowId}}\"}"},
                {"restart_service", "POST", "http://localhost:8080/mock/target/services/{{service}}/restart", "{}"},
                {"escalate", "POST", "http://localhost:8080/mock/target/escalations/{{team}}", "{\"team\":\"{{team}}\",\"alertType\":\"{{type}}\"}"},
                {"open_ticket", "POST", "http://localhost:8080/mock/target/tickets", "{\"summary\":\"{{type}} on {{service}}\"}"}}) {
            ObjectNode d = Json.obj().put("type", a[0]).put("method", a[1]).put("urlTemplate", a[2]).put("bodyTemplate", a[3]);
            Db.exec("INSERT INTO action_types (type, doc) VALUES (?, ?::jsonb) ON CONFLICT DO NOTHING", a[0], d);
        }
        for (String h : new String[]{"localhost", "api", "counterparty-service"}) Db.exec("INSERT INTO host_allowlist (host, team_id) VALUES (?, NULL)", h);
    }

    private static void user(String id, String username, String display, boolean admin, ObjectNode quiet) {
        ObjectNode u = Json.obj().put("id", id).put("username", username).put("displayName", display)
                .put("email", username + "@example.test").put("phone", "+44 7700 900" + String.format("%03d", Math.abs(username.hashCode()) % 1000)).put("platformAdmin", admin);
        ObjectNode ch = u.putObject("channels");
        ch.putArray("critical").add("sms").add("email");
        ch.putArray("error").add("email");
        ch.putArray("warning").add("email");
        ch.putArray("info");
        ch.putArray("unknown");
        if (username.equals("anna")) { ch.putArray("critical").add("sms"); }
        u.set("quietHours", quiet != null ? quiet : Json.obj().put("enabled", false).put("start", "20:00").put("end", "08:00").put("timezone", "Europe/London").put("fallbackChannel", "email"));
        Store.save("user", u);
    }

    private static void team(String id, String name, String notify, String onCall, String... members) {
        ObjectNode t = Json.obj().put("id", id).put("name", name).put("notify", notify).put("onCallUserId", onCall);
        ArrayNode m = t.putArray("members");
        for (String s : members) { String[] p = s.split(":"); m.addObject().put("userId", p[0]).put("role", p[1]); }
        Store.save("team", t);
    }

    private static void schema(String id, String team, String name, String matchSql, String sample, String severityConst) {
        ObjectNode inferred = Normaliser.infer(Json.parse(sample));
        ObjectNode s = Json.obj().put("id", id).put("teamId", team).put("name", name).put("priority", 10);
        ObjectNode match = SqlParser.parse(matchSql);
        s.set("match", match);
        s.put("matchSql", Filter.print(match));
        s.set("mapping", inferred.get("mapping"));
        if (severityConst != null) ((ObjectNode) s.get("mapping").get("severity")).put("const", severityConst);
        s.set("fields", inferred.get("fields"));
        s.set("sample", Json.parse(sample));
        Store.save("schema", s);
    }

    private static void source(String id, String team, String name, String type, String schema) {
        ObjectNode s = Json.obj().put("id", id).put("teamId", team).put("name", name).put("type", type).put("defaultSchemaId", schema).put("token", "demo-" + id);
        s.putArray("grantedTeamIds");
        Store.save("source", s);
    }

    private static void runbook(String id, String team, String content) {
        ObjectNode rb = Runbooks.parse(content, null);
        rb.put("id", id).put("teamId", team).put("version", 1);
        Store.save("runbook", rb);
    }

    private static ObjectNode rule(String id, String team, String name, String sql, String source) {
        ObjectNode r = Json.obj().put("id", id).put("teamId", team).put("name", name).put("enabled", true).put("version", 1);
        ObjectNode ast = SqlParser.parse(sql);
        r.set("filter", ast);
        r.put("filterSql", Filter.print(ast));
        r.putArray("sourceIds").add(source);
        r.putArray("allowedSources").add(source);
        r.putNull("window").putNull("severity").putNull("runbookId").putNull("resolveWhen");
        r.put("resolveWhenSql", "").put("renotifyMinutes", 0);
        return r;
    }

    private static void present(ObjectNode r, String title, String body, String color, String... fields) {
        ObjectNode p = r.putObject("presentation").put("titleTemplate", title).put("bodyTemplate", body).put("color", color);
        ArrayNode f = p.putArray("fields");
        for (String x : fields) f.add(x);
    }
}
