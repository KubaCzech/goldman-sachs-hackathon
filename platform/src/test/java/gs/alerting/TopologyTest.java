package gs.alerting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gs.alerting.core.Config;
import gs.alerting.core.Json;
import gs.alerting.core.SqlParser;
import gs.alerting.stream.AlertTopology;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Phase 2 gate: the topology behaves as SPEC 8 says, driven only by records on topics. */
class TopologyTest {
    private TopologyTestDriver driver;
    private TestInputTopic<String, String> cfg, settlement, metrics, trades, commands;
    private TestOutputTopic<String, String> alerts, notifications, matches;
    private TestOutputTopic<String, byte[]> dlq;
    private long now = Instant.parse("2026-10-23T09:00:00Z").toEpochMilli();

    @BeforeEach
    void setUp() {
        Properties p = new Properties();
        p.put(StreamsConfig.APPLICATION_ID_CONFIG, "test");
        p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        p.put(StreamsConfig.STATE_DIR_CONFIG, System.getProperty("java.io.tmpdir") + "/kstreams-test-" + System.nanoTime());
        Config registry = new Config();
        driver = new TopologyTestDriver(AlertTopology.build(registry), p, Instant.ofEpochMilli(now));
        StringSerializer s = new StringSerializer();
        cfg = driver.createInputTopic("cfg", s, s);
        settlement = driver.createInputTopic("ingest.settlement-bus", s, s);
        metrics = driver.createInputTopic("ingest.metrics-webhook", s, s);
        trades = driver.createInputTopic("ingest.trade-gateway", s, s);
        commands = driver.createInputTopic("alert.commands", s, s);
        StringDeserializer d = new StringDeserializer();
        alerts = driver.createOutputTopic("alerts", d, d);
        notifications = driver.createOutputTopic("notifications", d, d);
        matches = driver.createOutputTopic("alerting.matches", d, d);
        dlq = driver.createOutputTopic("ingest.dlq", d, new ByteArrayDeserializer());

        // Base config arrives the same way production config does: as records on the cfg topic.
        Config fixture = Samples.baseConfig();
        fixture.snapshot().sources.forEach((id, doc) -> cfg.pipeInput("source:" + id, Json.str(doc)));
        fixture.snapshot().teams.forEach((id, doc) -> cfg.pipeInput("team:" + id, Json.str(doc)));
        fixture.snapshot().users.forEach((id, doc) -> cfg.pipeInput("user:" + id, Json.str(doc)));
    }

    @AfterEach
    void tearDown() { driver.close(); }

    private void putRule(ObjectNode r) { cfg.pipeInput("rule:" + r.get("id").asText(), Json.str(r)); }

    /** TopologyTestDriver feeds records written to alerting.matches straight back into the second sub-topology. */
    private void pump() { }

    private JsonNode lastAlert() {
        List<String> all = alerts.readValuesToList();
        assertFalse(all.isEmpty(), "expected at least one alert");
        return Json.parse(all.get(all.size() - 1));
    }

    @Test
    void fiveHundredIdenticalErrorsBecomeOneAlertWithACounter() {
        ObjectNode r = Samples.rule("settle", "settlements", "type = 'WORKFLOW_FAILED' AND workflowName = 'order-settlement' AND environment = 'prod'", "settlement-bus");
        r.putArray("groupBy").add("workflowName").add("step");
        r.putArray("channels").add("phone").add("sms");
        r.putObject("presentation").put("titleTemplate", "{{workflowName}} failed at {{step}}").putArray("fields").add("step").add("error.message");
        putRule(r);
        for (int i = 0; i < 500; i++) settlement.pipeInput(null, Samples.WORKFLOW, now + i);
        pump();
        List<String> out = alerts.readValuesToList();
        assertEquals(500, out.size());
        JsonNode first = Json.parse(out.get(0)), last = Json.parse(out.get(499));
        assertEquals(first.get("alertId"), last.get("alertId"));
        assertEquals(500, last.get("count").asInt());
        assertEquals("OPEN", last.get("status").asText());
        assertEquals("order-settlement failed at validate-counterparty", last.get("title").asText());
        assertEquals("error", last.get("severity").asText());
        assertEquals("Counterparty service did not respond in 5000 ms", last.get("fields").get("error.message").asText());
        // Two members, two forced channels, notified once (not 500 times).
        List<String> ns = notifications.readValuesToList();
        assertEquals(4, ns.size());
        assertTrue(ns.stream().map(Json::parse).allMatch(n -> List.of("phone", "sms").contains(n.get("channel").asText())));
    }

    @Test
    void differentGroupValuesOpenSeparateAlerts() {
        ObjectNode r = Samples.rule("settle", "settlements", "type = 'WORKFLOW_FAILED'", "settlement-bus");
        r.putArray("groupBy").add("step");
        putRule(r);
        settlement.pipeInput(null, Samples.WORKFLOW, now);
        settlement.pipeInput(null, Samples.WORKFLOW.replace("validate-counterparty", "book-trade"), now);
        pump();
        List<String> out = alerts.readValuesToList();
        assertEquals(2, out.size());
        assertNotEquals(Json.parse(out.get(0)).get("alertId"), Json.parse(out.get(1)).get("alertId"));
    }

    @Test
    void recipientPreferencesDecideChannelsWhenTheRuleDoesNotForceThem() {
        putRule(Samples.rule("settle", "settlements", "type = 'WORKFLOW_FAILED'", "settlement-bus"));
        settlement.pipeInput(null, Samples.WORKFLOW, now);
        pump();
        List<String> ns = notifications.readValuesToList();
        assertEquals(2, ns.size());                       // severity error -> email for both members
        JsonNode n = Json.parse(ns.get(0));
        assertEquals("email", n.get("channel").asText());
        assertTrue(n.get("link").asText().endsWith("/alerts/" + n.get("alertId").asText()));
    }

    @Test
    void onCallPolicySendsToTheOnCallPersonOnly() {
        Config fixture = Samples.baseConfig();
        ObjectNode team = (ObjectNode) fixture.snapshot().teams.get("settlements");
        team.put("notify", "oncall");
        cfg.pipeInput("team:settlements", Json.str(team));
        putRule(Samples.rule("settle", "settlements", "type = 'WORKFLOW_FAILED'", "settlement-bus"));
        settlement.pipeInput(null, Samples.WORKFLOW, now);
        pump();
        List<String> ns = notifications.readValuesToList();
        assertEquals(1, ns.size());
        assertEquals("u-sam", Json.parse(ns.get(0)).get("userId").asText());
    }

    @Test
    void windowedRuleFiresOnlyAtTheThreshold() {
        ObjectNode r = Samples.rule("burst", "settlements", "type = 'WORKFLOW_FAILED'", "settlement-bus");
        r.putObject("window").put("count", 5).put("seconds", 300);
        putRule(r);
        for (int i = 0; i < 4; i++) settlement.pipeInput(null, Samples.WORKFLOW, now + i * 1000);
        pump();
        assertTrue(alerts.isEmpty(), "below threshold: no alert");
        settlement.pipeInput(null, Samples.WORKFLOW, now + 5000);
        pump();
        JsonNode a = lastAlert();
        assertEquals(5, a.get("count").asInt());
    }

    @Test
    void windowedRuleDoesNotFireWhenEventsAreSpreadOut() {
        ObjectNode r = Samples.rule("burst", "settlements", "type = 'WORKFLOW_FAILED'", "settlement-bus");
        r.putObject("window").put("count", 3).put("seconds", 60);
        putRule(r);
        for (int i = 0; i < 6; i++) settlement.pipeInput(null, Samples.WORKFLOW, now + i * 45_000L);
        assertTrue(alerts.isEmpty());
    }

    @Test
    void resolveConditionClosesTheAlertAndARecurrenceOpensANewOne() {
        ObjectNode r = Samples.rule("pay", "settlements", "alertname = 'HighErrorRate' AND status = 'firing'", "metrics-webhook");
        r.set("resolveWhen", SqlParser.parse("alertname = 'HighErrorRate' AND status = 'resolved'"));
        putRule(r);
        metrics.pipeInput(null, Samples.METRIC, now);
        pump();
        JsonNode open = lastAlert();
        assertEquals("OPEN", open.get("status").asText());
        assertEquals("critical", open.get("severity").asText());
        metrics.pipeInput(null, Samples.METRIC.replace("firing", "resolved"), now + 1000);
        pump();
        JsonNode closed = lastAlert();
        assertEquals("RESOLVED", closed.get("status").asText());
        assertEquals("auto", closed.get("resolution").asText());
        assertEquals(open.get("alertId"), closed.get("alertId"));
        metrics.pipeInput(null, Samples.METRIC, now + 2000);
        pump();
        JsonNode again = lastAlert();
        assertEquals("OPEN", again.get("status").asText());
        assertNotEquals(open.get("alertId"), again.get("alertId"));
        assertEquals(1, again.get("count").asInt());
    }

    @Test
    void ackAndResolveCommandsMoveTheAlertThroughItsLifecycle() {
        putRule(Samples.rule("settle", "settlements", "type = 'WORKFLOW_FAILED'", "settlement-bus"));
        settlement.pipeInput(null, Samples.WORKFLOW, now);
        pump();
        JsonNode a = lastAlert();
        String key = a.get("alertKey").asText(), id = a.get("alertId").asText();
        commands.pipeInput(key, "{\"kind\":\"COMMAND\",\"alertId\":\"" + id + "\",\"action\":\"ACK\",\"userId\":\"u-sam\",\"ts\":" + now + "}");
        JsonNode acked = lastAlert();
        assertEquals("ACKNOWLEDGED", acked.get("status").asText());
        assertEquals("u-sam", acked.get("ackBy").asText());
        // A command for a different (stale) alert id is ignored.
        commands.pipeInput(key, "{\"kind\":\"COMMAND\",\"alertId\":\"a-stale\",\"action\":\"RESOLVE\",\"userId\":\"u-sam\"}");
        assertTrue(alerts.isEmpty());
        commands.pipeInput(key, "{\"kind\":\"COMMAND\",\"alertId\":\"" + id + "\",\"action\":\"RESOLVE\",\"userId\":\"u-sara\",\"note\":\"restarted\"}");
        JsonNode res = lastAlert();
        assertEquals("RESOLVED", res.get("status").asText());
        assertEquals("manual", res.get("resolution").asText());
        assertEquals("restarted", res.get("note").asText());
    }

    @Test
    void rulesChangeAtRuntimeWithoutRestart() {
        trades.pipeInput(null, Samples.TRADE, now);
        pump();
        assertTrue(alerts.isEmpty(), "no rule yet");
        ObjectNode r = Samples.rule("rates", "settlements", "meta.schema LIKE 'trade.rejected.%' AND payload.notional > 10000000", "trade-gateway");
        r.putObject("presentation").put("titleTemplate", "Rejected {{payload.currency}} {{payload.notional}} — {{payload.reason}}");
        putRule(r);
        trades.pipeInput(null, Samples.TRADE, now);
        pump();
        assertEquals("Rejected EUR 25000000 — LIMIT_BREACH", lastAlert().get("title").asText());
        // Tighten the rule: same event no longer matches.
        r.set("filter", SqlParser.parse("payload.notional > 90000000"));
        putRule(r);
        trades.pipeInput(null, Samples.TRADE, now);
        pump();
        assertTrue(alerts.isEmpty());
        // Delete the rule (tombstone).
        matches.readValuesToList();
        cfg.pipeInput("rule:rates", (String) null);
        trades.pipeInput(null, Samples.TRADE, now);
        assertTrue(matches.isEmpty());
    }

    @Test
    void aRuleNeverSeesASourceItHasNoGrantFor() {
        // The rule would match trade events, but its allowedSources only lists settlement-bus.
        putRule(Samples.rule("nosy", "settlements", "payload.notional > 1", "settlement-bus"));
        matches.readValuesToList();
        trades.pipeInput(null, Samples.TRADE, now);
        assertTrue(matches.isEmpty());
    }

    @Test
    void anyTextFormatBecomesEventsAndOnlyBinaryGoesToTheDeadLetterTopic() {
        putRule(Samples.rule("settle", "settlements", "type = 'WORKFLOW_FAILED'", "settlement-bus"));
        putRule(Samples.rule("words", "settlements", "message LIKE '%timed out%'", "settlement-bus"));
        settlement.pipeInput(null, "2026-10-23 ERROR settlement timed out for WF-1", now);
        assertEquals(1, matches.readValuesToList().size(), "plain text is an event with a message");
        settlement.pipeInput(null, "type,workflowName\nWORKFLOW_FAILED,a\nWORKFLOW_FAILED,b\n", now);
        assertEquals(2, matches.readValuesToList().size(), "each CSV row is an event");
        settlement.pipeInput(null, "[" + Samples.WORKFLOW + "," + Samples.WORKFLOW + "]", now);
        assertEquals(2, matches.readValuesToList().size(), "a JSON array is split");
        assertTrue(dlq.readValuesToList().isEmpty());
        settlement.pipeInput(null, "\u0000\u0001\u0002\u0003\u0004\u0005\u0006", now);
        assertEquals(1, dlq.readValuesToList().size(), "binary goes to the dead-letter topic");
    }
}
