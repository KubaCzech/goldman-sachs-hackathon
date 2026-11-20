package gs.alerting.util;

import io.javalin.Javalin;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Liveness, readiness and Prometheus-format metrics for every role (SPEC 3). */
public final class Health {
    private Health() {}

    public static void register(Javalin app, BooleanSupplier ready, Supplier<Map<String, Number>> metrics) {
        app.get("/healthz", ctx -> ctx.result("ok"));
        app.get("/readyz", ctx -> { if (ready.getAsBoolean()) ctx.result("ready"); else ctx.status(503).result("not ready"); });
        app.get("/metrics", ctx -> {
            StringBuilder sb = new StringBuilder();
            metrics.get().forEach((k, v) -> sb.append(k).append(' ').append(v).append('\n'));
            ctx.contentType("text/plain; version=0.0.4").result(sb.toString());
        });
    }

    public static Javalin start(int port, BooleanSupplier ready, Supplier<Map<String, Number>> metrics) {
        Javalin app = Javalin.create(c -> c.showJavalinBanner = false);
        register(app, ready, metrics);
        app.start(port);
        return app;
    }
}
