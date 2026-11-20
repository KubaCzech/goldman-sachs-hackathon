package gs.alerting;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gs.alerting.core.Config;
import gs.alerting.core.Json;
import gs.alerting.core.SqlParser;

/** Appendix A / B samples and a small config fixture shared by tests. */
public final class Samples {
    public static final String WORKFLOW = """
            {"type":"WORKFLOW_FAILED","workflowId":"WF-20261023-00042","workflowName":"order-settlement","step":"validate-counterparty",
             "severity":"ERROR","environment":"prod","timestamp":"2026-10-23T09:14:02.331Z",
             "error":{"code":"TIMEOUT","message":"Counterparty service did not respond in 5000 ms"}}""";
    public static final String METRIC = """
            {"alertname":"HighErrorRate","labels":{"service":"payments-api","region":"eu-west","severity":"critical"},
             "annotations":{"summary":"Error rate 12% over the last 5 minutes"},"startsAt":"2026-10-23T09:13:00Z","status":"firing"}""";
    public static final String TRADE = """
            {"meta":{"schema":"trade.rejected.v2","producer":"trade-gateway","ts":1792833245000},
             "payload":{"tradeId":"T-998877","desk":"rates","notional":25000000,"currency":"EUR","reason":"LIMIT_BREACH"}}""";

    public static final String RB_YAML = """
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
    public static final String RB_MD = """
            # HighErrorRate on payments-api
            1. Open the payments dashboard and check if errors are in one region only.
            2. If one region: fail traffic over to the other region (needs approval from the team lead).
            3. If all regions: check the last deployment and roll it back.
            4. Post an update in the #payments-incidents channel every 15 minutes.
            """;
    public static final String RB_TEXT = "When pricing latency goes over a second, it's usually the cache. Restart the pricing-engine instance named in the alert, wait 2 minutes, and if latency is still high, call the pricing on-call person.";

    private Samples() {}

    public static ObjectNode rule(String id, String team, String sql, String source) {
        ObjectNode r = Json.obj();
        r.put("id", id).put("teamId", team).put("name", id).put("enabled", true);
        r.set("filter", SqlParser.parse(sql));
        r.putArray("allowedSources").add(source);
        r.putArray("groupBy");
        r.putArray("targets").addObject().put("type", "team").put("id", team);
        r.putObject("presentation");
        return r;
    }

    public static void put(Config c, String kind, ObjectNode doc) { c.apply(kind + ":" + doc.get("id").asText(), Json.str(doc)); }

    /** Two teams, three sources, users with preferences. */
    public static Config baseConfig() {
        Config c = new Config();
        for (String[] s : new String[][]{{"settlement-bus", "settlements"}, {"metrics-webhook", "payments"}, {"trade-gateway", "rates"}}) {
            ObjectNode src = Json.obj();
            src.put("id", s[0]).put("teamId", s[1]).put("type", "kafka");
            put(c, "source", src);
        }
        ObjectNode team = Json.obj();
        team.put("id", "settlements").put("name", "Settlements").put("notify", "all").put("onCallUserId", "u-sam");
        team.putArray("members").addObject().put("userId", "u-sam").put("role", "admin");
        ((com.fasterxml.jackson.databind.node.ArrayNode) team.get("members")).addObject().put("userId", "u-sara").put("role", "member");
        put(c, "team", team);
        for (String u : new String[]{"u-sam", "u-sara"}) {
            ObjectNode user = Json.obj();
            user.put("id", u).put("email", u + "@example.test").put("phone", "+4400" + u.hashCode() % 1000);
            ObjectNode ch = user.putObject("channels");
            ch.putArray("critical").add("sms").add("email");
            ch.putArray("error").add("email");
            ch.putArray("warning").add("email");
            ch.putArray("info");
            ch.putArray("unknown");
            put(c, "user", user);
        }
        return c;
    }
}
