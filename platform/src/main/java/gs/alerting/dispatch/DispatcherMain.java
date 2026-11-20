package gs.alerting.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gs.alerting.core.Json;
import gs.alerting.util.Db;
import gs.alerting.util.Env;
import gs.alerting.util.Health;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ROLE=dispatcher (SPEC 9). At-least-once consumption plus an idempotency key in Postgres gives
 * effectively-once delivery; failures go through a retry topic with backoff and end in a dead-letter topic.
 */
public final class DispatcherMain {
    private static final Logger log = LoggerFactory.getLogger(DispatcherMain.class);
    private static final long[] BACKOFF_MS = {1000, 5000, 15000, 60000};
    static final int MAX_ATTEMPTS = 5;
    static final AtomicLong SENT = new AtomicLong(), RETRIED = new AtomicLong(), FAILED = new AtomicLong(), DUPLICATES = new AtomicLong();
    static KafkaProducer<String, String> producer;
    static volatile Map<String, Boolean> down = Map.of();
    static volatile long downLoadedAt = 0;

    /** The only thing a real provider has to implement. */
    public interface ChannelAdapter {
        String channel();
        void send(JsonNode notification) throws Exception;
    }

    /** Adding a provider: write one adapter class and list it here. */
    static final class ChannelAdapters {
        static List<ChannelAdapter> all() { return List.of(new EmailAdapter(), new MockGatewayAdapter("sms"), new MockGatewayAdapter("phone")); }
    }

    static final class EmailAdapter implements ChannelAdapter {
        private final Session session;
        EmailAdapter() {
            Properties p = new Properties();
            p.put("mail.smtp.host", Env.get("SMTP_HOST", "localhost"));
            p.put("mail.smtp.port", Env.get("SMTP_PORT", "1025"));
            p.put("mail.smtp.connectiontimeout", "3000");
            p.put("mail.smtp.timeout", "5000");
            session = Session.getInstance(p);
        }
        public String channel() { return "email"; }
        public void send(JsonNode n) throws Exception {
            MimeMessage m = new MimeMessage(session);
            m.setFrom(new InternetAddress(Env.get("MAIL_FROM", "alerts@alerting.test")));
            m.setRecipient(Message.RecipientType.TO, new InternetAddress(Json.text(n, "address")));
            m.setSubject("[" + Json.text(n, "severity", "unknown").toUpperCase() + "] " + Json.text(n, "title", "Alert"), "UTF-8");
            StringBuilder b = new StringBuilder();
            String body = Json.text(n, "body");
            if (body != null && !body.isBlank()) b.append(body).append("\n\n");
            JsonNode f = n.get("fields");
            if (f != null && f.isObject() && f.size() > 0) { f.fields().forEachRemaining(en -> b.append(en.getKey()).append(": ").append(Json.text(en.getValue())).append('\n')); b.append('\n'); }
            String rb = Json.text(n, "runbookText");
            if (rb != null && !rb.isBlank()) b.append(rb).append('\n');
            b.append("Open the alert: ").append(Json.text(n, "link")).append('\n');
            m.setText(b.toString(), "UTF-8");
            Transport.send(m);
        }
    }

    /** Mock SMS / phone gateway: the "delivery" is a log line; the delivery row is what the UI shows. */
    static final class MockGatewayAdapter implements ChannelAdapter {
        private final String channel;
        MockGatewayAdapter(String channel) { this.channel = channel; }
        public String channel() { return channel; }
        public void send(JsonNode n) {
            String text = Json.text(n, "title", "Alert") + " " + Json.text(n, "link", "");
            if (text.length() > 160) text = text.substring(0, 157) + "...";
            log.info("MOCK {} to {}: {}", channel.toUpperCase(), Json.text(n, "address"), text);
        }
    }

    public static void run() throws Exception {
        Db.init();
        Env.awaitTopics(List.of("notifications", "notifications.retry", "notifications.dlq"));
        producer = new KafkaProducer<>(Env.producer(false));
        Map<String, ChannelAdapter> adapters = new LinkedHashMap<>();
        for (ChannelAdapter a : ChannelAdapters.all()) adapters.put(a.channel(), a);
        Thread main = new Thread(() -> loop("notifications", adapters), "dispatch-main");
        Thread retry = new Thread(() -> loop("notifications.retry", adapters), "dispatch-retry");
        main.start();
        retry.start();
        Health.start(Env.getInt("PORT", 8082), () -> main.isAlive() && retry.isAlive() && Db.ok(), () -> {
            Map<String, Number> m = new LinkedHashMap<>();
            m.put("alerting_deliveries_sent_total", SENT.get());
            m.put("alerting_deliveries_retried_total", RETRIED.get());
            m.put("alerting_deliveries_failed_total", FAILED.get());
            m.put("alerting_deliveries_duplicates_skipped_total", DUPLICATES.get());
            return m;
        });
        log.info("Dispatcher started with channels {}", adapters.keySet());
    }

    private static boolean isDown(String channel) {
        long now = System.currentTimeMillis();
        if (now - downLoadedAt > 1000) {
            Map<String, Boolean> m = new LinkedHashMap<>();
            for (ObjectNode r : Db.query("SELECT channel, down FROM channel_status")) m.put(Json.text(r, "channel"), r.path("down").asBoolean());
            down = m;
            downLoadedAt = now;
        }
        return down.getOrDefault(channel, false);
    }

    private static void loop(String topic, Map<String, ChannelAdapter> adapters) {
        while (true) {
            try (KafkaConsumer<String, String> c = new KafkaConsumer<>(Env.consumer("dispatcher", true))) {
                c.subscribe(List.of(topic));
                while (true) {
                    ConsumerRecords<String, String> recs = c.poll(Duration.ofMillis(200));
                    for (ConsumerRecord<String, String> r : recs) handle(r, adapters);
                    if (!recs.isEmpty()) { producer.flush(); c.commitSync(); }
                }
            } catch (Exception e) {
                log.error("Dispatcher loop on {} failed; restarting", topic, e);
                try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private static void handle(ConsumerRecord<String, String> rec, Map<String, ChannelAdapter> adapters) throws Exception {
        ObjectNode n;
        try { n = (ObjectNode) Json.M.readTree(rec.value()); } catch (Exception e) { return; }
        String id = Json.text(n, "notificationId"), channel = Json.text(n, "channel", "");
        long notBefore = n.path("notBefore").asLong(0), now = System.currentTimeMillis();
        if (notBefore > now) Thread.sleep(Math.min(notBefore - now, 60_000));   // only ever true on the retry topic
        int attempt = n.path("attempt").asInt(0);
        now = System.currentTimeMillis();
        Db.exec("INSERT INTO deliveries (notification_id, alert_id, user_id, team_id, channel, address, title, status, attempt, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?) ON CONFLICT (notification_id) DO NOTHING",
                id, Json.text(n, "alertId"), Json.text(n, "userId"), Json.text(n, "teamId"), channel, Json.text(n, "address"), Json.text(n, "title"), attempt, now, now);
        ObjectNode row = Db.one("SELECT status FROM deliveries WHERE notification_id = ?", id);
        if (row != null && "SENT".equals(Json.text(row, "status"))) { DUPLICATES.incrementAndGet(); return; }   // idempotency key
        try {
            ChannelAdapter a = adapters.get(channel);
            if (a == null) throw new IllegalStateException("No adapter for channel '" + channel + "'");
            if (isDown(channel)) throw new IllegalStateException("Channel '" + channel + "' is down");
            a.send(n);
            long done = System.currentTimeMillis();
            Db.exec("UPDATE deliveries SET status = 'SENT', attempt = ?, latency_ms = ?, error = NULL, updated_at = ? WHERE notification_id = ?",
                    attempt, done - n.path("ingestTs").asLong(done), done, id);
            SENT.incrementAndGet();
        } catch (Exception e) {
            int next = attempt + 1;
            String err = e.getMessage() == null ? e.toString() : e.getMessage();
            if (next < MAX_ATTEMPTS) {
                n.put("attempt", next).put("notBefore", System.currentTimeMillis() + BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)]);
                producer.send(new ProducerRecord<>("notifications.retry", rec.key(), Json.str(n)));
                Db.exec("UPDATE deliveries SET status = 'RETRYING', attempt = ?, error = ?, updated_at = ? WHERE notification_id = ?", next, err, System.currentTimeMillis(), id);
                RETRIED.incrementAndGet();
            } else {
                producer.send(new ProducerRecord<>("notifications.dlq", rec.key(), Json.str(n)));
                Db.exec("UPDATE deliveries SET status = 'FAILED', attempt = ?, error = ?, updated_at = ? WHERE notification_id = ?", next, err, System.currentTimeMillis(), id);
                FAILED.incrementAndGet();
                log.warn("Giving up on notification {} after {} attempts: {}", id, next, err);
            }
        }
    }
}
