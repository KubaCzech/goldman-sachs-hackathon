package gs.alerting.api;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Notes written during an incident become reusable runbook text: this incident's values turn into placeholders. */
class IncidentDraftTest {
    @Test
    void longerValuesAreReplacedFirst() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("WF-20261023-00042-B", "relatedWorkflowId");   // sorted longest first, as build() does
        p.put("WF-20261023-00042", "workflowId");
        p.put("counterparty-service", "service");
        assertEquals("Restarted {{service}}, then retried {{workflowId}} and {{relatedWorkflowId}}",
                IncidentDraft.templatise("Restarted counterparty-service, then retried WF-20261023-00042 and WF-20261023-00042-B", p));
    }
}
