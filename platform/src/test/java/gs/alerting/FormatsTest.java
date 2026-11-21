package gs.alerting;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gs.alerting.core.Formats;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Every supported input format, detected without being told, gives the same fields a JSON sender would. */
class FormatsTest {
    private static Formats.Parsed auto(String text) { return Formats.parse(text, "auto"); }

    @Test
    void jsonObjectArrayAndNdjson() {
        assertEquals("json", auto(Samples.METRIC).format);
        assertEquals(1, auto(Samples.METRIC).events.size());
        Formats.Parsed arr = auto("[{\"a\":1},{\"a\":2}]");
        assertEquals(2, arr.events.size());
        Formats.Parsed nd = auto("{\"a\":1}\n{\"a\":2}\n{\"a\":3}");
        assertEquals("ndjson", nd.format);
        assertEquals(3, nd.events.size());
        assertEquals(3, nd.events.get(2).path("a").asInt());
    }

    @Test
    void csvWithTypesQuotesAndNestedHeaders() {
        Formats.Parsed p = auto("""
                tradeId,payload.desk,notional,reason,flagged
                T-1,rates,25000000,"LIMIT, BREACH",true
                T-2,fx,1500.5,"said \"\"no\"\"",false
                """);
        assertEquals("csv", p.format);
        assertEquals(2, p.events.size());
        ObjectNode first = p.events.get(0);
        assertEquals("rates", first.path("payload").path("desk").asText(), "a dotted header becomes a nested field");
        assertTrue(first.path("notional").isNumber(), "numbers become numbers so notional > 10000000 works");
        assertEquals(25_000_000L, first.path("notional").asLong());
        assertEquals("LIMIT, BREACH", first.path("reason").asText());
        assertTrue(first.path("flagged").isBoolean());
        assertEquals("said \"no\"", p.events.get(1).path("reason").asText());
        assertEquals(1500.5, p.events.get(1).path("notional").asDouble());
    }

    @Test
    void semicolonCsvAndTsv() {
        assertEquals(2, auto("a;b\n1;2\n3;4").events.size());
        Formats.Parsed tsv = auto("service\tlevel\npayments\tERROR");
        assertEquals("tsv", tsv.format);
        assertEquals("ERROR", tsv.events.get(0).path("level").asText());
    }

    @Test
    void yamlSingleAndMultiDocument() {
        Formats.Parsed p = auto("""
                type: WORKFLOW_FAILED
                workflowName: order-settlement
                error:
                  code: TIMEOUT
                  retries: 3
                """);
        assertEquals("yaml", p.format);
        assertEquals("TIMEOUT", p.events.get(0).path("error").path("code").asText());
        assertEquals(3, p.events.get(0).path("error").path("retries").asInt());
        assertEquals(2, auto("---\na: 1\n---\na: 2\n").events.size());
    }

    @Test
    void xmlAttributesChildrenAndBatches() {
        Formats.Parsed one = auto("<trade id=\"T-9\"><desk>rates</desk><notional>25000000</notional><tag>a</tag><tag>b</tag></trade>");
        assertEquals("xml", one.format);
        ObjectNode t = one.events.get(0);
        assertEquals("T-9", t.path("id").asText());
        assertEquals(25_000_000L, t.path("notional").asLong());
        assertEquals(2, t.path("tag").size(), "repeated elements become a list");
        Formats.Parsed batch = auto("<events><event><a>1</a></event><event><a>2</a></event></events>");
        assertEquals(2, batch.events.size());
    }

    @Test
    void xmlExternalEntitiesAreRefused() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>";
        Formats.Parsed p = auto(xxe);
        assertNotEquals("xml", p.format, "a DOCTYPE is rejected; the body is kept as text instead");
        assertFalse(p.events.toString().contains("root:"), "no file content may be read");
    }

    @Test
    void logfmtAndForm() {
        Formats.Parsed p = auto("level=error service=payments-api latency_ms=1240 msg=\"cache miss on eu-west\"\nlevel=warn service=pricing latency_ms=900");
        assertEquals("logfmt", p.format);
        assertEquals(2, p.events.size());
        assertEquals("cache miss on eu-west", p.events.get(0).path("msg").asText());
        assertEquals(1240, p.events.get(0).path("latency_ms").asInt());
        Formats.Parsed f = Formats.parse("alertname=Disk&labels.service=db&value=93", "form");
        assertEquals("db", f.events.get(0).path("labels").path("service").asText());
    }

    @Test
    void syslogBothFlavours() {
        Formats.Parsed p = auto("""
                <11>1 2026-10-23T09:14:02Z host-7 settlement-svc 4242 ID47 - Counterparty timeout on WF-1
                <12>Oct 23 09:14:02 host-8 pricing[311]: latency above 1s
                """);
        assertEquals("syslog", p.format);
        assertEquals("error", p.events.get(0).path("level").asText(), "PRI 11 = facility 1, severity 3 (error)");
        assertEquals("settlement-svc", p.events.get(0).path("app").asText());
        assertEquals("warning", p.events.get(1).path("level").asText());
        assertEquals(311, p.events.get(1).path("pid").asInt());
    }

    @Test
    void plainTextBecomesOneEventPerLineWithAGuessedLevel() {
        Formats.Parsed p = auto("2026-10-23 09:14 ERROR settlement timed out for WF-1\nall good here");
        assertEquals("text", p.format);
        assertEquals(2, p.events.size());
        assertEquals("error", p.events.get(0).path("level").asText());
        assertFalse(p.events.get(1).has("level"));
    }

    @Test
    void forcedFormatThatDoesNotFitIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> Formats.parse("not json", "json"));
    }

    @Test
    void binaryIsRefused() {
        byte[] bin = {0, 1, 2, 3, (byte) 0xff, (byte) 0xfe, 4, 5};
        assertThrows(Formats.NotText.class, () -> Formats.parse(bin, "auto"));
        assertEquals(1, Formats.parse("hello".getBytes(StandardCharsets.UTF_8), "auto").events.size());
    }

    @Test
    void contentTypes() {
        assertEquals("csv", Formats.fromContentType("text/csv; charset=utf-8"));
        assertEquals("yaml", Formats.fromContentType("application/x-yaml"));
        assertEquals("ndjson", Formats.fromContentType("application/x-ndjson"));
        assertNull(Formats.fromContentType("text/plain"));
        assertNull(Formats.fromContentType("application/x-www-form-urlencoded"), "curl -d labels everything as a form");
        byte[] csv = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);
        assertEquals("csv", Formats.parse(csv, null, "application/json").format, "a mislabelled body is detected instead");
        assertThrows(IllegalArgumentException.class, () -> Formats.parse(csv, "json", null), "a source's own format is strict");
        assertTrue(List.of("json", "xml", "form").contains(Formats.fromContentType("application/xml")));
    }
}
