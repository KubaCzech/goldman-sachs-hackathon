package gs.alerting;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gs.alerting.core.AlertEngine;
import gs.alerting.core.Config;
import gs.alerting.core.Event;
import gs.alerting.core.Json;
import gs.alerting.core.Normaliser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A private rule notifies only its owner (whatever its targets say) and marks its alerts with the owner. */
class PrivateRuleTest {
    @Test
    void privateRuleNotifiesOnlyItsOwnerAndTagsTheAlert() {
        Config c = Samples.baseConfig();
        ObjectNode rule = Samples.rule("mine", "settlements", "type = 'WORKFLOW_FAILED'", "settlement-bus");   // targets: the whole team
        rule.put("ownerUserId", "u-sara");
        Samples.put(c, "rule", rule);
        Config.Snapshot cfg = c.snapshot();
        Event e = Normaliser.normalise("settlement-bus", Json.parse(Samples.WORKFLOW), 1000, 2000, cfg);

        AlertEngine.Result r = AlertEngine.onMatch(null, cfg.rules.get("mine"), e, false, cfg, 1000, 2000);
        assertEquals("u-sara", r.alert.path("ownerUserId").asText());
        assertFalse(r.notifications.isEmpty());
        assertTrue(r.notifications.stream().allMatch(n -> "u-sara".equals(n.path("userId").asText())), "only the owner is notified");

        rule.remove("ownerUserId");                           // the same rule as a team rule notifies the team
        Samples.put(c, "rule", rule);
        cfg = c.snapshot();
        r = AlertEngine.onMatch(null, cfg.rules.get("mine"), e, false, cfg, 1000, 2000);
        assertTrue(r.alert.path("ownerUserId").isMissingNode());
        assertTrue(r.notifications.stream().anyMatch(n -> "u-sam".equals(n.path("userId").asText())));
    }
}
