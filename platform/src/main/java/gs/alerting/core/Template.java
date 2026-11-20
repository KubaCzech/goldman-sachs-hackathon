package gs.alerting.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Logic-less {{path}} templates (SPEC 7). */
public final class Template {
    private static final Pattern P = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");

    private Template() {}

    public static String render(String tpl, Function<String, JsonNode> resolver, List<String> unresolved) {
        if (tpl == null || tpl.isEmpty()) return "";
        if (tpl.indexOf("{{") < 0) return tpl;
        Matcher m = P.matcher(tpl);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String path = m.group(1);
            String v = Json.text(resolver.apply(path));
            if (v == null) {
                if (unresolved != null && !unresolved.contains(path)) unresolved.add(path);
                v = "⟦missing: " + path + "⟧";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String render(String tpl, Event e, List<String> unresolved) {
        return render(tpl, e::resolve, unresolved);
    }
}
