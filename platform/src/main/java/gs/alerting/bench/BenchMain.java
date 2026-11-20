package gs.alerting.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gs.alerting.core.Json;
import gs.alerting.util.Env;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * ROLE=bench (SPEC 13). Produces the three sample schemas straight to the ingest topics and measures
 * ingest → alert-update and ingest → notification latency from what comes out of the pipeline.
 * Every event matches a seeded rule, so the sum of alert counters must equal the number of events sent.
 */
public final class BenchMain {
    public static void run() throws Exception {
        int events = Env.getInt("BENCH_EVENTS", 200_000), rate = Env.getInt("BENCH_RATE", 20_000), keys = Env.getInt("BENCH_KEYS", 200);
        String runId = "b" + Long.toString(System.currentTimeMillis(), 36);
        Env.awaitTopics(List.of("ingest.settlement-bus", "ingest.metrics-webhook", "ingest.trade-gateway", "alerts", "notifications"));

        KafkaConsumer<String, String> alerts = tail("alerts"), notes = tail("notifications");
        System.out.printf("Benchmark %s: %d events at %d/s, %d distinct alert groups%n", runId, events, rate, keys);

        long[] alertLat = new long[events + 1024];
        int nAlert = 0;
        List<Long> noteLat = new ArrayList<>();
        Map<String, Long> counts = new HashMap<>(), base = new HashMap<>();
        long sent = 0, start = System.nanoTime(), startMs = System.currentTimeMillis(), lastSeen = startMs;

        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Env.producer(true))) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            while (sent < events || System.currentTimeMillis() - lastSeen < 5000) {
                long due = Math.min(events, (long) ((System.nanoTime() - start) / 1e9 * rate));
                for (; sent < due; sent++) {
                    int k = rnd.nextInt(keys);
                    String topic, json;
                    switch ((int) (sent % 3)) {
                        case 0 -> { topic = "ingest.settlement-bus"; json = "{\"type\":\"WORKFLOW_FAILED\",\"workflowId\":\"WF-" + sent + "\",\"workflowName\":\"order-settlement\",\"step\":\"" + runId + "-step-" + k + "\",\"severity\":\"ERROR\",\"environment\":\"prod\",\"timestamp\":\"2026-10-23T09:14:02.331Z\",\"error\":{\"code\":\"TIMEOUT\",\"message\":\"Counterparty service did not respond in 5000 ms\"}}"; }
                        case 1 -> { topic = "ingest.metrics-webhook"; json = "{\"alertname\":\"HighErrorRate\",\"labels\":{\"service\":\"payments-api\",\"region\":\"" + runId + "-r" + k + "\",\"severity\":\"critical\"},\"annotations\":{\"summary\":\"Error rate 12% over the last 5 minutes\"},\"startsAt\":\"2026-10-23T09:13:00Z\",\"status\":\"firing\"}"; }
                        default -> { topic = "ingest.trade-gateway"; json = "{\"meta\":{\"schema\":\"trade.rejected.v2\",\"producer\":\"trade-gateway\",\"ts\":1792833245000},\"payload\":{\"tradeId\":\"" + runId + "-T" + k + "\",\"desk\":\"rates\",\"notional\":25000000,\"currency\":\"EUR\",\"reason\":\"LIMIT_BREACH\"}}"; }
                    }
                    ProducerRecord<String, byte[]> rec = new ProducerRecord<>(topic, null, json.getBytes(StandardCharsets.UTF_8));
                    rec.headers().add("ingest-ts", String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.US_ASCII));
                    producer.send(rec);
                }
                long now = System.currentTimeMillis();
                for (ConsumerRecord<String, String> r : alerts.poll(Duration.ofMillis(5))) {
                    if (r.value() == null || !r.value().contains(runId)) continue;
                    JsonNode a = Json.M.readTree(r.value());
                    long cnt = a.path("count").asLong();
                    base.putIfAbsent(r.key(), cnt - 1);            // an alert that was already open keeps its earlier count out of the tally
                    counts.merge(r.key(), cnt - base.get(r.key()), Math::max);
                    if (nAlert < alertLat.length) alertLat[nAlert++] = Math.max(0, r.timestamp() > 0 ? now - a.path("ingestTs").asLong(now) : 0);
                    lastSeen = now;
                }
                for (ConsumerRecord<String, String> r : notes.poll(Duration.ofMillis(1))) {
                    if (r.value() == null) continue;
                    JsonNode n = Json.M.readTree(r.value());
                    if (n.path("ingestTs").asLong() >= startMs) { noteLat.add(Math.max(0, now - n.path("ingestTs").asLong())); lastSeen = now; }
                }
                if (sent >= events && counts.values().stream().mapToLong(Long::longValue).sum() >= events) break;
                if (System.currentTimeMillis() - startMs > Env.getInt("BENCH_TIMEOUT_S", 600) * 1000L) break;
            }
        }
        double seconds = (lastSeen - startMs) / 1000.0;
        long processed = counts.values().stream().mapToLong(Long::longValue).sum();
        long[] al = Arrays.copyOf(alertLat, nAlert);
        Arrays.sort(al);
        long[] nl = noteLat.stream().mapToLong(Long::longValue).sorted().toArray();

        ObjectNode res = Json.obj();
        res.put("runId", runId).put("eventsSent", sent).put("eventsAccountedFor", processed).put("lost", sent - processed)
                .put("targetRatePerSec", rate).put("durationSec", seconds).put("throughputPerSec", Math.round(processed / Math.max(0.001, seconds)))
                .put("alertGroups", counts.size()).put("alertUpdatesObserved", nAlert).put("notificationsObserved", nl.length);
        res.set("ingestToAlertMs", pct(al));
        res.set("ingestToNotificationMs", pct(nl));
        res.put("processingGuarantee", Env.get("PROCESSING_GUARANTEE", "exactly_once_v2")).put("note", "Latency is measured by this process on receipt, from the ingest timestamp header to the record being readable (read_committed).");

        System.out.println("\n================ Benchmark result ================");
        System.out.printf("Events sent            %,d%n", sent);
        System.out.printf("Events accounted for   %,d (lost: %,d)%n", processed, sent - processed);
        System.out.printf("Duration               %.1f s%n", seconds);
        System.out.printf("Throughput             %,d events/s end to end%n", res.get("throughputPerSec").asLong());
        System.out.printf("Alert groups           %,d (deduplicated from %,d events)%n", counts.size(), sent);
        System.out.printf("Ingest -> alert        p50 %d ms, p95 %d ms, p99 %d ms%n", p(al, 50), p(al, 95), p(al, 99));
        System.out.printf("Ingest -> notification p50 %d ms, p95 %d ms, p99 %d ms (%d notifications)%n", p(nl, 50), p(nl, 95), p(nl, 99), nl.length);
        Path dir = Path.of(Env.get("BENCH_OUT", "bench/results"));
        Files.createDirectories(dir);
        Path out = dir.resolve(runId + ".json");
        Files.writeString(out, Json.M.writerWithDefaultPrettyPrinter().writeValueAsString(res));
        System.out.println("Written to " + out);
        System.exit(0);
    }

    private static KafkaConsumer<String, String> tail(String topic) {
        KafkaConsumer<String, String> c = new KafkaConsumer<>(Env.consumer("bench-" + UUID.randomUUID(), false));
        List<TopicPartition> tps = c.partitionsFor(topic).stream().map(p -> new TopicPartition(topic, p.partition())).collect(Collectors.toList());
        c.assign(tps);
        c.seekToEnd(tps);
        tps.forEach(c::position);
        return c;
    }

    private static long p(long[] sorted, int pct) { return sorted.length == 0 ? -1 : sorted[Math.min(sorted.length - 1, (int) Math.ceil(pct / 100.0 * sorted.length) - 1)]; }

    private static ObjectNode pct(long[] sorted) {
        return Json.obj().put("p50", p(sorted, 50)).put("p95", p(sorted, 95)).put("p99", p(sorted, 99)).put("max", sorted.length == 0 ? -1 : sorted[sorted.length - 1]).put("samples", sorted.length);
    }
}
