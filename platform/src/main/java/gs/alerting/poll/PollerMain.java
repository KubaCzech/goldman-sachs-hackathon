package gs.alerting.poll;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gs.alerting.core.Json;
import gs.alerting.util.Db;
import gs.alerting.util.Env;
import gs.alerting.util.Health;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ROLE=poller: HTTP polling sources. Instead of waiting for a webhook, the service fetches a URL on a schedule and
 * sends the response into the source's ingest topic, where it is parsed (any format) and matched like any other
 * event. By default only a changed response is sent. When the URL stops answering, a POLL_FAILED event is sent (and
 * POLL_RECOVERED when it answers again), so "the endpoint is down" can be alerted on with an ordinary rule.
 *
 * Scaling: POLLER_SHARDS / POLLER_INDEX split the sources between instances (stable hash of the source id).
 */
public final class PollerMain {
    private static final Logger log = LoggerFactory.getLogger(PollerMain.class);
    static final AtomicLong POLLS = new AtomicLong(), SENT = new AtomicLong(), FAILURES = new AtomicLong(), UNCHANGED = new AtomicLong();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();

    /** Per-source memory: last response hash, whether the last poll failed, when it is due next. */
    static final class State {
        String lastHash;
        boolean failing;
        long nextAt;
        volatile boolean running;
    }

    private static final Map<String, State> states = new ConcurrentHashMap<>();

    private PollerMain() {}

    public static void run() throws Exception {
        Db.init();
        int shards = Math.max(1, Env.getInt("POLLER_SHARDS", 1)), index = Env.getInt("POLLER_INDEX", 0);
        Map<String, String> hostMap = hostMap(Env.get("POLL_HOST_MAP", ""));
        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Env.producer(true));
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        Health.start(Env.getInt("PORT", 8083), Db::ok, () -> {
            Map<String, Number> m = new LinkedHashMap<>();
            m.put("alerting_polls_total", POLLS.get());
            m.put("alerting_poll_events_sent_total", SENT.get());
            m.put("alerting_poll_unchanged_total", UNCHANGED.get());
            m.put("alerting_poll_failures_total", FAILURES.get());
            return m;
        });
        log.info("Poller started (shard {} of {})", index, shards);
        while (true) {
            long now = System.currentTimeMillis();
            Set<String> live = new HashSet<>();
            try {
                for (ObjectNode row : Db.query("SELECT doc FROM config WHERE kind = 'source'")) {
                    JsonNode s = row.get("doc");
                    String id = Json.text(s, "id");
                    if (!"poll".equals(Json.text(s, "type")) || id == null || Math.floorMod(id.hashCode(), shards) != index) continue;
                    live.add(id);
                    State st = states.computeIfAbsent(id, k -> new State());
                    if (st.running || now < st.nextAt) continue;
                    st.running = true;
                    st.nextAt = now + Math.max(5, s.path("poll").path("intervalSeconds").asInt(30)) * 1000L;
                    pool.submit(() -> { try { pollOnce(s, st, producer, hostMap); } finally { st.running = false; } });
                }
                states.keySet().retainAll(live);   // deleted sources stop being polled
            } catch (Exception e) {
                log.warn("Could not load poll sources: {}", e.toString());
            }
            Thread.sleep(1000);
        }
    }

    static void pollOnce(JsonNode source, State st, KafkaProducer<String, byte[]> producer, Map<String, String> hostMap) {
        String id = Json.text(source, "id"), team = Json.text(source, "teamId");
        JsonNode cfg = source.path("poll");
        String url = Json.text(cfg, "url", "");
        boolean onlyChanges = cfg.path("onlyWhenChanged").asBoolean(true);
        POLLS.incrementAndGet();
        long started = System.currentTimeMillis();
        int status = 0;
        String error = null, contentType = null;
        byte[] body = null;
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) throw new IllegalArgumentException("Not an http(s) URL");
            if (!allowed(uri.getHost(), team)) throw new IllegalArgumentException("Host '" + uri.getHost() + "' is not on the allowlist");
            URI target = mapHost(uri, hostMap);
            HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5)).GET()
                    .header("User-Agent", "alerting-poller").build(), HttpResponse.BodyHandlers.ofByteArray());
            status = r.statusCode();
            contentType = r.headers().firstValue("Content-Type").orElse(null);
            body = r.body();
            if (status < 200 || status >= 300) error = "HTTP " + status;
            else if (body == null || body.length == 0) error = "Empty response";
        } catch (Exception e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        long took = System.currentTimeMillis() - started;
        boolean sent = false;
        if (error == null) {
            String hash = Json.sha1Hex(new String(body, StandardCharsets.ISO_8859_1));
            if (st.failing) send(producer, id, event(id, url, "POLL_RECOVERED", "info", "The URL answers again (HTTP " + status + ")", status, took), "application/json");
            st.failing = false;
            if (onlyChanges && hash.equals(st.lastHash)) UNCHANGED.incrementAndGet();
            else { send(producer, id, body, contentType); sent = true; }
            st.lastHash = hash;
        } else {
            FAILURES.incrementAndGet();
            if (!st.failing) send(producer, id, event(id, url, "POLL_FAILED", "error", error, status, took), "application/json");   // once per outage
            st.failing = true;
        }
        try {
            Db.exec("INSERT INTO poll_status (source_id, last_poll_at, http_status, ok, error, took_ms, events_sent, last_sent_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (source_id) DO UPDATE SET last_poll_at = EXCLUDED.last_poll_at, "
                    + "http_status = EXCLUDED.http_status, ok = EXCLUDED.ok, error = EXCLUDED.error, took_ms = EXCLUDED.took_ms, "
                    + "events_sent = poll_status.events_sent + EXCLUDED.events_sent, last_sent_at = COALESCE(EXCLUDED.last_sent_at, poll_status.last_sent_at)",
                    id, started, status, error == null, error, took, sent ? 1 : 0, sent ? started : null);
        } catch (Exception e) {
            log.warn("Could not record poll status for {}: {}", id, e.toString());
        }
    }

    static byte[] event(String sourceId, String url, String type, String severity, String message, int status, long took) {
        ObjectNode e = Json.obj().put("type", type).put("severity", severity).put("service", sourceId).put("url", url)
                .put("message", message).put("httpStatus", status).put("tookMs", took).put("timestamp", java.time.Instant.now().toString());
        return Json.str(e).getBytes(StandardCharsets.UTF_8);
    }

    private static void send(KafkaProducer<String, byte[]> producer, String sourceId, byte[] body, String contentType) {
        ProducerRecord<String, byte[]> rec = new ProducerRecord<>("ingest." + sourceId, null, body);
        rec.headers().add("ingest-ts", String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.US_ASCII));
        if (contentType != null) rec.headers().add("content-type", contentType.getBytes(StandardCharsets.UTF_8));
        producer.send(rec);
        SENT.incrementAndGet();
    }

    /** Same allowlist as runbook actions: a source cannot make the service call arbitrary hosts. */
    static boolean allowed(String host, String teamId) {
        for (ObjectNode r : Db.query("SELECT host FROM host_allowlist WHERE team_id IS NULL OR team_id = ?", teamId))
            if (host.equalsIgnoreCase(Json.text(r, "host"))) return true;
        return false;
    }

    /** POLL_HOST_MAP "localhost=api,foo=bar:9000": lets a URL typed as seen from a laptop work inside the compose network. */
    static Map<String, String> hostMap(String spec) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String p : spec.split(",")) {
            int i = p.indexOf('=');
            if (i > 0) m.put(p.substring(0, i).trim(), p.substring(i + 1).trim());
        }
        return m;
    }

    static URI mapHost(URI u, Map<String, String> hostMap) {
        String to = hostMap.get(u.getHost());
        if (to == null) return u;
        String host = to, port = u.getPort() < 0 ? "" : ":" + u.getPort();
        if (to.contains(":")) { host = to.substring(0, to.indexOf(':')); port = to.substring(to.indexOf(':')); }
        return URI.create(u.getScheme() + "://" + host + port + (u.getRawPath() == null ? "" : u.getRawPath()) + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery()));
    }
}
