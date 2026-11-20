package gs.alerting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gs.alerting.core.Config;
import gs.alerting.core.Event;
import gs.alerting.core.Filter;
import gs.alerting.core.Json;
import gs.alerting.core.Normaliser;
import gs.alerting.core.Runbooks;
import gs.alerting.core.SqlParser;
import gs.alerting.core.Template;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    private static Event ev(String source, String json, Config c) {
        return Normaliser.normalise(source, Json.parse(json), 1000, 2000, c.snapshot());
    }

    private static boolean matches(String sql, Event e) { return Filter.compile(SqlParser.parse(sql)).test(e); }

    @Test
    void normalisesTheThreeSampleSchemasWithoutAnyRegisteredSchema() {
        Config c = Samples.baseConfig();
        Event w = ev("settlement-bus", Samples.WORKFLOW, c);
        assertEquals("error", w.severity);
        assertEquals("WORKFLOW_FAILED", w.type);
        assertEquals("order-settlement", w.service);
        assertEquals(Instant.parse("2026-10-23T09:14:02.331Z").toEpochMilli(), w.eventTime);
        assertEquals("settlements", w.ownerTeamId);
        assertEquals("unknown", w.schemaId);

        Event m = ev("metrics-webhook", Samples.METRIC, c);
        assertEquals("critical", m.severity);
        assertEquals("HighErrorRate", m.type);
        assertEquals("payments-api", m.service);

        Event t = ev("trade-gateway", Samples.TRADE, c);
        assertEquals("unknown", t.severity);
        assertEquals("trade.rejected.v2", t.type);
        assertEquals(1792833245000L, t.eventTime);
    }

    @Test
    void schemaMatchPredicateAndMappingAreApplied() {
        Config c = Samples.baseConfig();
        ObjectNode s = Json.obj();
        s.put("id", "trade-rejected").put("priority", 10);
        s.set("match", SqlParser.parse("meta.schema LIKE 'trade.rejected.%'"));
        ObjectNode map = s.putObject("mapping");
        map.putObject("severity").put("const", "warning");
        map.putObject("service").put("path", "payload.desk");
        Samples.put(c, "schema", s);
        Event t = ev("trade-gateway", Samples.TRADE, c);
        assertEquals("trade-rejected", t.schemaId);
        assertEquals("warning", t.severity);
        assertEquals("rates", t.service);
        assertEquals("unknown", ev("settlement-bus", Samples.WORKFLOW, c).schemaId);
    }

    @Test
    void exampleRuleFiltersEvaluate() {
        Config c = Samples.baseConfig();
        Event w = ev("settlement-bus", Samples.WORKFLOW, c), m = ev("metrics-webhook", Samples.METRIC, c), t = ev("trade-gateway", Samples.TRADE, c);
        assertTrue(matches("type = 'WORKFLOW_FAILED' AND workflowName = 'order-settlement' AND environment = 'prod'", w));
        assertFalse(matches("type = 'WORKFLOW_FAILED' AND environment = 'uat'", w));
        assertTrue(matches("meta.schema LIKE 'trade.rejected.%' AND payload.desk = 'rates' AND payload.notional > 10000000", t));
        assertFalse(matches("payload.notional > 30000000", t));
        assertTrue(matches("severity = 'critical' AND labels.service = 'payments-api'", m));
        assertTrue(matches("severity >= 'WARN'", w));
        assertFalse(matches("severity >= 'critical'", w));
        assertTrue(matches("error.code IN ('TIMEOUT', 'REJECTED')", w));
        assertTrue(matches("error.code NOT IN ('A', 'B')", w));
        assertTrue(matches("payload.notional BETWEEN 1000000 AND 30000000", t));
        assertTrue(matches("(type = 'x' OR source = 'settlement-bus') AND NOT (step = 'other')", w));
        assertTrue(matches("eventTime BETWEEN 1792000000000 AND 1793000000000", t));
        // Missing fields never match, and are detectable.
        assertFalse(matches("nope.field = 'x'", w));
        assertFalse(matches("nope.field != 'x'", w));
        assertTrue(matches("nope.field IS NULL AND step IS NOT NULL", w));
        assertTrue(matches("", w));
    }

    @Test
    void sqlRoundTripsAndReportsGuiCompatibility() {
        String[] sqls = {
                "type = 'WORKFLOW_FAILED' AND environment = 'prod'",
                "a.b > 10 AND (c = 'x' OR d IN ('p', 'q')) AND NOT (e = 1)",
                "x NOT BETWEEN 1 AND 5 OR y NOT LIKE 'a%' OR z IS NOT NULL",
                "name = 'O''Brien' AND flag = TRUE AND ratio <= 0.25",
                "\"weird field\" = 'v'"};
        for (String sql : sqls) {
            ObjectNode ast = SqlParser.parse(sql);
            String printed = Filter.print(ast);
            assertEquals(ast, SqlParser.parse(printed), "round trip of: " + sql + " -> " + printed);
        }
        assertEquals("a = 1 AND (b = 2 OR c = 3)", Filter.print(SqlParser.parse("a=1 and (b=2 or c=3)")));
        assertTrue(Filter.guiCompatible(SqlParser.parse("a = 1 AND b IN (1,2) AND c BETWEEN 1 AND 2 AND d >= 5")));
        assertTrue(Filter.guiCompatible(SqlParser.parse("a = 1")));
        assertFalse(Filter.guiCompatible(SqlParser.parse("a = 1 OR b = 2")));
        assertFalse(Filter.guiCompatible(SqlParser.parse("a = 1 AND NOT (b = 2)")));
        assertFalse(Filter.guiCompatible(SqlParser.parse("a LIKE 'x%'")));
    }

    @Test
    void sqlErrorsCarryAPosition() {
        SqlParser.ParseException ex = assertThrows(SqlParser.ParseException.class, () -> SqlParser.parse("a = 1 AND"));
        assertEquals(9, ex.position);
        assertThrows(SqlParser.ParseException.class, () -> SqlParser.parse("a = banana"));
        assertThrows(SqlParser.ParseException.class, () -> SqlParser.parse("a = 'x' ; DROP TABLE alerts"));
        assertThrows(SqlParser.ParseException.class, () -> SqlParser.parse("(a = 1"));
    }

    @Test
    void templatesFillValuesAndMarkMissingOnes() {
        Event t = ev("trade-gateway", Samples.TRADE, Samples.baseConfig());
        List<String> missing = new ArrayList<>();
        assertEquals("Rejected EUR 25000000 — LIMIT_BREACH",
                Template.render("Rejected {{payload.currency}} {{ payload.notional }} — {{payload.reason}}", t, missing));
        assertTrue(missing.isEmpty());
        String s = Template.render("Retry {{workflowId}} on {{source}}", t, missing);
        assertEquals("Retry ⟦missing: workflowId⟧ on trade-gateway", s);
        assertEquals(List.of("workflowId"), missing);
    }

    @Test
    void runbooksImportFromYamlMarkdownTextAndUnknownFormats() {
        ObjectNode y = Runbooks.parse(Samples.RB_YAML, null);
        assertEquals("yaml", y.get("format").asText());
        assertEquals("settlement-timeout", y.get("name").asText());
        assertEquals("TIMEOUT", y.get("matches").get("error.code").asText());
        assertEquals(3, y.get("steps").size());
        JsonNode s0 = y.get("steps").get(0), s1 = y.get("steps").get(1), s2 = y.get("steps").get(2);
        assertEquals("Is counterparty-service healthy?", s0.get("text").asText());
        assertEquals("http_get", s0.get("action").get("type").asText());
        assertEquals("http://counterparty-service/health", s0.get("action").get("params").get("url").asText());
        assertEquals("none", s0.get("approval").asText());
        assertEquals("retry_workflow", s1.get("action").get("type").asText());
        assertEquals("none", s1.get("approval").asText());
        assertEquals("retry fails twice", s2.get("condition").asText());
        assertEquals("required", s2.get("approval").asText());

        ObjectNode md = Runbooks.parse(Samples.RB_MD, null);
        assertEquals("markdown", md.get("format").asText());
        assertEquals("HighErrorRate on payments-api", md.get("name").asText());
        assertEquals(4, md.get("steps").size());
        assertEquals("required", md.get("steps").get(1).get("approval").asText());

        ObjectNode txt = Runbooks.parse(Samples.RB_TEXT, null);
        assertEquals("text", txt.get("format").asText());
        assertEquals(1, txt.get("steps").size());

        ObjectNode odd = Runbooks.parse("<runbook><step>Reboot it</step></runbook>", null);
        assertEquals("text", odd.get("format").asText());
        assertTrue(odd.get("steps").get(0).get("text").asText().contains("Reboot it"));
        assertEquals("<runbook><step>Reboot it</step></runbook>", odd.get("content").asText());
    }

    @Test
    void runbookIsSelectedByMatchesAndRenderedWithValues() {
        Config c = Samples.baseConfig();
        ObjectNode rb = Runbooks.parse(Samples.RB_YAML, null);
        rb.put("id", "settlement-timeout").put("teamId", "settlements");
        Samples.put(c, "runbook", rb);
        Samples.put(c, "rule", Samples.rule("r1", "settlements", "type = 'WORKFLOW_FAILED'", "settlement-bus"));
        Event w = ev("settlement-bus", Samples.WORKFLOW, c);
        JsonNode sel = Runbooks.select(c.snapshot().rules.get("r1"), w, c.snapshot());
        assertNotNull(sel);
        String text = Runbooks.renderText(sel, w);
        assertTrue(text.contains("Retry workflow WF-20261023-00042"), text);
        assertTrue(text.contains("needs approval"), text);
        assertNull(Runbooks.select(c.snapshot().rules.get("r1"), ev("settlement-bus", Samples.METRIC, c), c.snapshot()));
    }
}
