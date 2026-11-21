package gs.alerting;

import com.fasterxml.jackson.databind.JsonNode;
import gs.alerting.core.Json;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PollerTest {
    @Test
    void hostMapKeepsPortPathAndQuery() throws Exception {
        var m = gs.alerting.poll.PollerMain.class.getDeclaredMethod("hostMap", String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, String> map = (Map<String, String>) m.invoke(null, "localhost=api, foo=bar:9000");
        var map2 = gs.alerting.poll.PollerMain.class.getDeclaredMethod("mapHost", URI.class, Map.class);
        map2.setAccessible(true);
        assertEquals(URI.create("http://api:8080/mock/status/x?a=1"), map2.invoke(null, URI.create("http://localhost:8080/mock/status/x?a=1"), map));
        assertEquals(URI.create("http://bar:9000/p"), map2.invoke(null, URI.create("http://foo/p"), map));
        assertEquals(URI.create("http://other/p"), map2.invoke(null, URI.create("http://other/p"), map));
    }

    @Test
    void failureEventIsAnOrdinaryEventRulesCanMatch() throws Exception {
        var m = gs.alerting.poll.PollerMain.class.getDeclaredMethod("event", String.class, String.class, String.class, String.class, String.class, int.class, long.class);
        m.setAccessible(true);
        JsonNode e = Json.M.readTree(new String((byte[]) m.invoke(null, "pricing-status", "http://x/y", "POLL_FAILED", "error", "HTTP 503", 503, 12L), StandardCharsets.UTF_8));
        assertEquals("POLL_FAILED", e.path("type").asText());
        assertEquals("error", e.path("severity").asText());
        assertEquals(503, e.path("httpStatus").asInt());
    }
}
