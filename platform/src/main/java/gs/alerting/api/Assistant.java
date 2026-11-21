package gs.alerting.api;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gs.alerting.api.Auth.ApiError;
import gs.alerting.core.Filter;
import gs.alerting.core.Json;
import gs.alerting.core.SqlParser;
import gs.alerting.util.Env;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * "Describe it in words": turns a plain-language description into a filter in the service's own WHERE-clause language.
 *
 * The model is an assistant, never an authority:
 *  - it only runs when a person asks for it in an editor; nothing in the event stream calls a model;
 *  - what it writes goes through the same {@link SqlParser} as hand-typed text, so anything outside the grammar is
 *    rejected and history queries stay parameterised; the person sees the result and decides whether to keep it;
 *  - it is sent the description and the names and types of the available fields, never event contents;
 *  - the provider sits behind {@link Llm}, so a self-hosted model can replace the hosted one.
 */
final class Assistant {
    /** The only thing a model provider has to implement. */
    interface Llm {
        String complete(String system, String user) throws Exception;
        String name();
    }

    /** Set from the environment at start; null means the feature is off and the UI hides it. */
    static Llm llm = fromEnv();

    private Assistant() {}

    static Llm fromEnv() {
        String key = Env.get("ANTHROPIC_API_KEY", "");
        if (key.isBlank()) return null;
        String model = Env.get("LLM_MODEL", "claude-opus-5-5"), effort = Env.get("LLM_EFFORT", "low");
        AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofSeconds(40)).maxRetries(1).build();
        return new Llm() {
            public String name() { return model; }
            public String complete(String system, String user) {
                Message m = client.messages().create(MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(2000L)
                        .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(effort)).build())
                        .system(system)
                        .addUserMessage(user)
                        .build());
                if (m.stopReason().map(StopReason.REFUSAL::equals).orElse(false)) throw new IllegalStateException("The model declined this request");
                return m.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).collect(Collectors.joining()).trim();
            }
        };
    }

    static final String SYSTEM = """
            You translate a person's description into a filter for an alerting service.

            Write the filter in this language only (a subset of a SQL WHERE clause, without the word WHERE):
              condition := condition AND condition | condition OR condition | NOT condition | ( condition )
              comparison := field = value | field != value | field < value | field <= value | field > value | field >= value
                          | field IN (value, ...) | field NOT IN (value, ...) | field BETWEEN value AND value
                          | field LIKE 'pattern' | field IS NULL | field IS NOT NULL
            Fields are names from the list in the request; nested fields use dots (payload.notional). Text values go in
            single quotes; numbers are bare; LIKE patterns use % as the wildcard. There are no functions, no sub-queries,
            no arithmetic and no column lists.

            The field "severity" is ordered: unknown < info < warning < error < critical, so severity >= 'error' means
            error or critical. Use only fields from the list; if the description needs a value the person did not give
            (for example "large" without a number), choose the most literal reading and keep the filter simple.

            Reply with the filter on one line and nothing else: no explanation, no code fence, no trailing semicolon.
            If the description cannot be expressed with the listed fields and this language, reply with one line that
            starts with CANNOT: followed by a short reason the person can act on.
            """;

    /** @param scope "alerts" when searching stored alerts (alert fields such as status and count apply), else "rule". */
    static ObjectNode filterFromText(String text, ArrayNode fields, String scope) throws Exception {
        if (llm == null) throw new ApiError(503, "The assistant is not set up. Start the API with ANTHROPIC_API_KEY to turn it on.");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Describe what you are looking for");
        if (text.length() > 1000) throw new IllegalArgumentException("Keep the description under 1,000 characters");
        StringBuilder user = new StringBuilder("Available fields (name: type");
        user.append("alerts".equals(scope) ? "), for searching stored alerts:\n" : "), for a rule that decides which incoming events raise an alert:\n");
        Set<String> known = new LinkedHashSet<>();
        for (JsonNode f : fields) {
            if (!"alerts".equals(scope) && "alert".equals(Json.text(f, "scope"))) continue;
            String path = Json.text(f, "path");
            if (path == null || !known.add(path) || known.size() > 200) continue;
            user.append("- ").append(path).append(": ").append(Json.text(f, "type", "string"));
            // Values are listed only for the service's own fixed fields; values seen in events are never sent.
            if ("built-in".equals(Json.text(f, "origin")) && f.path("values").isArray() && !path.equals("source"))
                user.append(" (one of ").append(Json.strings(f.get("values"))).append(")");
            user.append('\n');
        }
        user.append("\nDescription:\n").append(text.trim());

        String prompt = user.toString(), answer = clean(llm.complete(SYSTEM, prompt));
        SqlParser.ParseException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (answer.regionMatches(true, 0, "CANNOT:", 0, 7))
                throw new ApiError(422, "The assistant could not write this as a filter: " + answer.substring(7).trim());
            try {
                ObjectNode ast = SqlParser.parse(answer);
                Filter.compile(ast);
                ObjectNode out = Json.obj();
                out.put("sql", Filter.print(ast)).put("guiCompatible", Filter.guiCompatible(ast)).put("model", llm.name());
                out.set("ast", ast);
                ArrayNode unknown = out.putArray("unknownFields");
                Set<String> used = new LinkedHashSet<>();
                collectFields(ast, used);
                for (String u : used) if (!known.contains(u)) unknown.add(u);
                return out;
            } catch (SqlParser.ParseException e) {      // one chance to fix its own mistake, told exactly what was wrong
                last = e;
                if (attempt == 0) answer = clean(llm.complete(SYSTEM, prompt + "\n\nYour previous reply was:\n" + answer
                        + "\nIt is not valid in this language: " + e.getMessage() + "\nReply with a corrected filter only."));
            }
        }
        throw new ApiError(422, "The assistant's filter was not valid (" + last.getMessage() + "). Try describing it differently, or write it in SQL.");
    }

    /** Models sometimes wrap the answer in a code fence or prefix it with WHERE despite being told not to. */
    static String clean(String s) {
        String t = s == null ? "" : s.trim();
        if (t.startsWith("```")) t = t.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```\\s*$", "");
        t = t.trim();
        if (t.regionMatches(true, 0, "WHERE ", 0, 6)) t = t.substring(6).trim();
        if (t.endsWith(";")) t = t.substring(0, t.length() - 1).trim();
        return t;
    }

    static void collectFields(JsonNode n, Set<String> out) {
        if (n == null) return;
        if (n.isObject()) {
            if (n.hasNonNull("field")) out.add(n.get("field").asText());
            n.fields().forEachRemaining(e -> collectFields(e.getValue(), out));
        } else if (n.isArray()) n.forEach(c -> collectFields(c, out));
    }
}
