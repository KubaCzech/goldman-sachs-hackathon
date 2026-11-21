package gs.alerting.api;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gs.alerting.core.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Whatever the model says, only text that our own parser accepts comes out; the model never sees event values. */
class AssistantTest {
    private final List<String> prompts = new ArrayList<>();

    private void fake(String... replies) {
        Deque<String> q = new ArrayDeque<>(List.of(replies));
        Assistant.llm = new Assistant.Llm() {
            public String complete(String system, String user) { prompts.add(user); return q.pop(); }
            public String name() { return "fake"; }
        };
    }

    private static ArrayNode fields() {
        ArrayNode a = Json.arr();
        a.addObject().put("path", "severity").put("type", "severity").put("origin", "built-in").put("scope", "event")
                .putArray("values").add("critical").add("error");
        a.addObject().put("path", "status").put("type", "string").put("origin", "built-in").put("scope", "alert");
        a.addObject().put("path", "payload.notional").put("type", "number").put("origin", "schema").put("scope", "event");
        a.addObject().put("path", "payload.desk").put("type", "string").put("origin", "observed").put("scope", "event")
                .putArray("values").add("SECRET-DESK-VALUE");
        return a;
    }

    @AfterEach void off() { Assistant.llm = null; }

    @Test
    void validAnswerIsParsedAndNormalised() throws Exception {
        fake("```sql\nWHERE severity >= 'error' AND (payload.notional > 10000000 OR payload.desk = 'rates');\n```");
        ObjectNode r = Assistant.filterFromText("big rejected trades or anything serious", fields(), "rule");
        assertEquals("severity >= 'error' AND (payload.notional > 10000000 OR payload.desk = 'rates')", r.get("sql").asText());
        assertEquals(0, r.get("unknownFields").size());
        assertFalse(prompts.get(0).contains("SECRET-DESK-VALUE"), "values seen in events are never sent to the model");
        assertFalse(prompts.get(0).contains("- status"), "alert-only fields are not offered for rules");
        assertTrue(prompts.get(0).contains("critical"), "the service's own fixed values are listed");
    }

    @Test
    void invalidAnswerGetsOneCorrectionThenIsRefused() throws Exception {
        fake("payload.notional >> 5", "payload.notional > 5");
        assertEquals("payload.notional > 5", Assistant.filterFromText("more than five", fields(), "rule").get("sql").asText());
        assertTrue(prompts.get(1).contains("not valid in this language"));

        fake("DROP TABLE alerts", "SELECT * FROM alerts");
        Auth.ApiError e = assertThrows(Auth.ApiError.class, () -> Assistant.filterFromText("delete everything", fields(), "alerts"));
        assertEquals(422, e.status);
    }

    @Test
    void cannotAndUnknownFieldsAreReported() throws Exception {
        fake("CANNOT: there is no field for the customer's name");
        Auth.ApiError e = assertThrows(Auth.ApiError.class, () -> Assistant.filterFromText("alerts for customer Acme", fields(), "alerts"));
        assertTrue(e.getMessage().contains("customer's name"));

        fake("made.up = 1");
        assertEquals("made.up", Assistant.filterFromText("x", fields(), "rule").get("unknownFields").get(0).asText());
    }

    @Test
    void offWithoutAProvider() {
        Assistant.llm = null;
        assertEquals(503, assertThrows(Auth.ApiError.class, () -> Assistant.filterFromText("x", fields(), "rule")).status);
    }
}
