package gs.alerting;

import gs.alerting.core.Config;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for events dropped at processor start-up: several stream threads ask for the first snapshot at the
 * same time. None of them may be handed a snapshot older than the config applied before the call (here: the empty
 * initial one), or events are silently dropped as "unknown source" / "rule missing".
 */
class ConfigConcurrencyTest {
    @Test
    void concurrentFirstSnapshotNeverReturnsAStaleOne() throws Exception {
        int threads = 8, rounds = 200;
        AtomicInteger stale = new AtomicInteger();
        for (int round = 0; round < rounds; round++) {
            Config c = Samples.baseConfig();
            for (int i = 0; i < 150; i++)     // enough rules that building a snapshot takes measurable time
                Samples.put(c, "rule", Samples.rule("r" + i, "settlements", "severity = 'critical' AND notional > " + i, "settlement-bus"));
            CyclicBarrier start = new CyclicBarrier(threads);
            Thread[] ts = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                ts[t] = new Thread(() -> {
                    try { start.await(); } catch (Exception e) { throw new RuntimeException(e); }
                    Config.Snapshot s = c.snapshot();
                    if (!s.sources.containsKey("metrics-webhook") || s.rulesFor("settlement-bus").size() != 150) stale.incrementAndGet();
                });
                ts[t].start();
            }
            for (Thread t : ts) t.join();
        }
        assertEquals(0, stale.get(), "threads were handed a snapshot that did not contain config applied before the call");
    }

    @Test
    void reapplyingUnchangedConfigDoesNotRebuild() {
        Config c = Samples.baseConfig();
        Samples.put(c, "rule", Samples.rule("r1", "settlements", "severity = 'critical'", "settlement-bus"));
        Config.Snapshot first = c.snapshot();
        Samples.put(c, "rule", Samples.rule("r1", "settlements", "severity = 'critical'", "settlement-bus"));   // as on task start
        assertSame(first, c.snapshot());
        Samples.put(c, "rule", Samples.rule("r1", "settlements", "severity = 'error'", "settlement-bus"));
        assertNotSame(first, c.snapshot());
    }
}
