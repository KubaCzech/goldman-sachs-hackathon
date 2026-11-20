package gs.alerting.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Filter AST: compile to a predicate, print as SQL, check GUI compatibility (SPEC 6). */
public final class Filter {
    public interface Pred { boolean test(Event e); }

    private static final Set<String> SIMPLE = Set.of("eq", "in", "between", "gt", "ge", "lt", "le");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_@][A-Za-z0-9_@-]*");
    private static final Set<String> KEYWORDS = Set.of("and", "or", "not", "in", "between", "like", "is", "null", "true", "false", "where");

    private Filter() {}

    public static ObjectNode alwaysTrue() {
        ObjectNode o = Json.obj();
        o.put("op", "and");
        o.putArray("args");
        return o;
    }

    // ---------------------------------------------------------------- compile

    public static Pred compile(JsonNode ast) {
        if (ast == null || ast.isNull()) return e -> true;
        String op = Json.text(ast, "op", "");
        switch (op) {
            case "and": {
                List<Pred> ps = children(ast);
                Pred[] a = ps.toArray(new Pred[0]);
                return e -> { for (Pred p : a) if (!p.test(e)) return false; return true; };
            }
            case "or": {
                List<Pred> ps = children(ast);
                Pred[] a = ps.toArray(new Pred[0]);
                return e -> { for (Pred p : a) if (p.test(e)) return true; return false; };
            }
            case "not": {
                Pred p = compile(ast.get("arg"));
                return e -> !p.test(e);
            }
            default:
        }
        String field = Json.text(ast, "field");
        if (field == null || field.isEmpty()) throw new IllegalArgumentException("Filter node '" + op + "' needs a field");
        boolean sev = field.equals("severity");
        switch (op) {
            case "isnull": return e -> e.resolve(field) == null;
            case "notnull": return e -> e.resolve(field) != null;
            case "eq": { JsonNode lit = req(ast, "value"); return e -> { Integer c = cmp(e.resolve(field), lit, sev); return c != null && c == 0; }; }
            case "ne": { JsonNode lit = req(ast, "value"); return e -> { Integer c = cmp(e.resolve(field), lit, sev); return c != null && c != 0; }; }
            case "gt": { JsonNode lit = req(ast, "value"); return e -> { Integer c = cmp(e.resolve(field), lit, sev); return c != null && c > 0; }; }
            case "ge": { JsonNode lit = req(ast, "value"); return e -> { Integer c = cmp(e.resolve(field), lit, sev); return c != null && c >= 0; }; }
            case "lt": { JsonNode lit = req(ast, "value"); return e -> { Integer c = cmp(e.resolve(field), lit, sev); return c != null && c < 0; }; }
            case "le": { JsonNode lit = req(ast, "value"); return e -> { Integer c = cmp(e.resolve(field), lit, sev); return c != null && c <= 0; }; }
            case "in": {
                List<JsonNode> vals = new ArrayList<>();
                req(ast, "values").forEach(vals::add);
                return e -> {
                    JsonNode v = e.resolve(field);
                    if (v == null) return false;
                    for (JsonNode lit : vals) { Integer c = cmp(v, lit, sev); if (c != null && c == 0) return true; }
                    return false;
                };
            }
            case "between": {
                JsonNode lo = req(ast, "low"), hi = req(ast, "high");
                return e -> {
                    JsonNode v = e.resolve(field);
                    Integer a = cmp(v, lo, sev), b = cmp(v, hi, sev);
                    return a != null && b != null && a >= 0 && b <= 0;
                };
            }
            case "like": {
                Pattern p = likePattern(Json.text(req(ast, "value")));
                return e -> { String s = Json.text(e.resolve(field)); return s != null && p.matcher(s).matches(); };
            }
            default: throw new IllegalArgumentException("Unknown filter operator '" + op + "'");
        }
    }

    private static List<Pred> children(JsonNode ast) {
        List<Pred> ps = new ArrayList<>();
        JsonNode args = ast.get("args");
        if (args != null) args.forEach(a -> ps.add(compile(a)));
        return ps;
    }

    private static JsonNode req(JsonNode ast, String k) {
        JsonNode n = ast.get(k);
        if (n == null || n.isNull()) throw new IllegalArgumentException("Filter node '" + Json.text(ast, "op") + "' needs '" + k + "'");
        return n;
    }

    static Pattern likePattern(String like) {
        StringBuilder sb = new StringBuilder();
        for (char c : like.toCharArray()) {
            if (c == '%') sb.append(".*");
            else if (c == '_') sb.append('.');
            else sb.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(sb.toString(), Pattern.DOTALL);
    }

    private static Double num(JsonNode n) {
        if (n.isNumber()) return n.asDouble();
        if (n.isTextual()) {
            String s = n.asText();
            if (s.isEmpty() || s.length() > 40) return null;
            char c = s.charAt(0);
            if (!(c == '-' || c == '.' || (c >= '0' && c <= '9'))) return null;
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /** Compare an event value to a literal. Null when the value is missing (comparison is then false). */
    static Integer cmp(JsonNode v, JsonNode lit, boolean severity) {
        if (v == null || lit == null) return null;
        if (severity) return Integer.compare(Severity.rank(Severity.normalise(v.asText())), Severity.rank(Severity.normalise(lit.asText())));
        if (v.isNumber() || lit.isNumber()) {
            Double a = num(v), b = num(lit);
            if (a != null && b != null) return Double.compare(a, b);
        }
        if (v.isBoolean() || lit.isBoolean()) return Integer.signum(v.asText().compareToIgnoreCase(lit.asText()));
        return Integer.signum(Json.text(v).compareTo(Json.text(lit)));
    }

    // ---------------------------------------------------------------- GUI compatibility

    public static boolean guiCompatible(JsonNode ast) {
        if (ast == null || ast.isNull()) return true;
        String op = Json.text(ast, "op", "");
        if (SIMPLE.contains(op)) return true;
        if (!op.equals("and")) return false;
        for (JsonNode a : ast.path("args")) if (!SIMPLE.contains(Json.text(a, "op", ""))) return false;
        return true;
    }

    // ---------------------------------------------------------------- print

    public static String print(JsonNode ast) {
        if (ast == null || ast.isNull()) return "";
        return print(ast, 0);
    }

    /** prec: 0 top, 1 inside OR, 2 inside AND, 3 inside NOT. */
    private static String print(JsonNode n, int prec) {
        String op = Json.text(n, "op", "");
        switch (op) {
            case "and": {
                JsonNode args = n.path("args");
                if (args.size() == 0) return "";
                if (args.size() == 1) return print(args.get(0), prec);
                List<String> parts = new ArrayList<>();
                args.forEach(a -> parts.add(print(a, 2)));
                String s = String.join(" AND ", parts);
                return prec > 2 ? "(" + s + ")" : s;
            }
            case "or": {
                JsonNode args = n.path("args");
                if (args.size() == 0) return "1 = 0";
                if (args.size() == 1) return print(args.get(0), prec);
                List<String> parts = new ArrayList<>();
                args.forEach(a -> parts.add(print(a, 1)));
                String s = String.join(" OR ", parts);
                return prec > 1 ? "(" + s + ")" : s;
            }
            case "not": {
                JsonNode a = n.get("arg");
                String inner = Json.text(a, "op", "");
                String f = "in".equals(inner) || "between".equals(inner) || "like".equals(inner) ? field(Json.text(a, "field")) : null;
                if ("in".equals(inner)) return f + " NOT IN (" + lits(a.get("values")) + ")";
                if ("between".equals(inner)) return f + " NOT BETWEEN " + lit(a.get("low")) + " AND " + lit(a.get("high"));
                if ("like".equals(inner)) return f + " NOT LIKE " + lit(a.get("value"));
                return "NOT (" + print(a, 0) + ")";
            }
            default:
        }
        String f = field(Json.text(n, "field"));
        return switch (op) {
            case "eq" -> f + " = " + lit(n.get("value"));
            case "ne" -> f + " != " + lit(n.get("value"));
            case "gt" -> f + " > " + lit(n.get("value"));
            case "ge" -> f + " >= " + lit(n.get("value"));
            case "lt" -> f + " < " + lit(n.get("value"));
            case "le" -> f + " <= " + lit(n.get("value"));
            case "like" -> f + " LIKE " + lit(n.get("value"));
            case "in" -> f + " IN (" + lits(n.get("values")) + ")";
            case "between" -> f + " BETWEEN " + lit(n.get("low")) + " AND " + lit(n.get("high"));
            case "isnull" -> f + " IS NULL";
            case "notnull" -> f + " IS NOT NULL";
            default -> throw new IllegalArgumentException("Unknown filter operator '" + op + "'");
        };
    }

    private static String field(String path) {
        if (path == null) throw new IllegalArgumentException("Filter node needs a field");
        for (String seg : path.split("\\.")) {
            if (!IDENT.matcher(seg).matches() || KEYWORDS.contains(seg.toLowerCase())) return "\"" + path.replace("\"", "") + "\"";
        }
        return path;
    }

    private static String lits(JsonNode arr) {
        List<String> parts = new ArrayList<>();
        if (arr != null) arr.forEach(a -> parts.add(lit(a)));
        return String.join(", ", parts);
    }

    private static String lit(JsonNode v) {
        if (v == null || v.isNull()) return "NULL";
        if (v.isNumber()) return v.asText();
        if (v.isBoolean()) return v.asBoolean() ? "TRUE" : "FALSE";
        return "'" + v.asText().replace("'", "''") + "'";
    }

    // ---------------------------------------------------------------- AST helpers

    public static ObjectNode leaf(String op, String field, JsonNode value) {
        ObjectNode o = Json.obj();
        o.put("op", op).put("field", field);
        if (value != null) o.set("value", value);
        return o;
    }

    public static ObjectNode group(String op, List<JsonNode> args) {
        ObjectNode o = Json.obj();
        o.put("op", op);
        ArrayNode a = o.putArray("args");
        args.forEach(a::add);
        return o;
    }

    public static ObjectNode not(JsonNode arg) {
        ObjectNode o = Json.obj();
        o.put("op", "not");
        o.set("arg", arg);
        return o;
    }
}
