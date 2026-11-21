package gs.alerting;

/** One image, one jar: the role is chosen by the ROLE environment variable (SPEC D12). */
public final class Main {
    public static void main(String[] args) throws Exception {
        String role = args.length > 0 ? args[0] : System.getenv().getOrDefault("ROLE", "api");
        switch (role) {
            case "api" -> gs.alerting.api.ApiMain.run();
            case "processor" -> gs.alerting.stream.ProcessorMain.run();
            case "dispatcher" -> gs.alerting.dispatch.DispatcherMain.run();
            case "poller" -> gs.alerting.poll.PollerMain.run();
            case "bench" -> gs.alerting.bench.BenchMain.run();
            default -> throw new IllegalArgumentException("Unknown ROLE '" + role + "' (api|processor|dispatcher|poller|bench)");
        }
    }
}
