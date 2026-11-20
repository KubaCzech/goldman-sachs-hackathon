package gs.alerting.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Compiles the filter AST to a parameterised Postgres predicate over the alerts table (SPEC 6.5). */
public final class FilterSql {
    private static final Map<String, String> TEXT_COLS = Map.of("source", "source_id", "schema", "doc->>'schemaId'", "type", "type",
            "service", "service", "status", "status", "team", "team_id", "rule", "rule_id", "title", "title");
    private static final Map<String, String> NUM_COLS = Map.of("eventTime", "last_seen", "receivedAt", "last_seen", "count", "count",
            "firstSeen", "first_seen", "lastSeen", "last_seen");

    private FilterSql() {}

    public static String compile(JsonNode ast, List<Object> params) {
        if (ast == null || ast.isNull()) return "TRUE";
        String op = Json.text(ast, "op", "");
        switch (op) {
            case "and", "or": {
                List<String> parts = new ArrayList<>();
                for (JsonNode a : ast.path("args")) parts.add(compile(a, params));
                if (parts.isEmpty()) return op.equals("and") ? "TRUE" : "FALSE";
                return "(" + String.join(op.equals("and") ? " AND " : " OR ", parts) + ")";
            }
            case "not": return "(NOT COALESCE(" + compile(ast.get("arg"), params) + ", FALSE))";
            default:
        }
        String field = Json.text(ast, "field", "");
        switch (op) {
            case "isnull": return "(" + textExpr(field, params) + " IS NULL)";
            case "notnull": return "(" + textExpr(field, params) + " IS NOT NULL)";
            case "in": {
                List<String> parts = new ArrayList<>();
                for (JsonNode v : ast.path("values")) parts.add(cmp(field, "=", v, params));
                return parts.isEmpty() ? "FALSE" : "(" + String.join(" OR ", parts) + ")";
            }
            case "between": return "(" + cmp(field, ">=", ast.get("low"), params) + " AND " + cmp(field, "<=", ast.get("high"), params) + ")";
            case "like": { String x = textExpr(field, params); params.add(Json.text(ast.get("value"))); return "(" + x + " LIKE ?)"; }
            case "eq": return cmp(field, "=", ast.get("value"), params);
            case "ne": return cmp(field, "<>", ast.get("value"), params);
            case "gt": return cmp(field, ">", ast.get("value"), params);
            case "ge": return cmp(field, ">=", ast.get("value"), params);
            case "lt": return cmp(field, "<", ast.get("value"), params);
            case "le": return cmp(field, "<=", ast.get("value"), params);
            default: throw new IllegalArgumentException("Unknown filter operator '" + op + "'");
        }
    }

    private static String cmp(String field, String sqlOp, JsonNode lit, List<Object> params) {
        if (lit == null || lit.isNull()) return "FALSE";
        if (field.equals("severity")) {
            params.add(Severity.rank(Severity.normalise(lit.asText())));
            return "(severity_rank " + sqlOp + " ?)";
        }
        if (NUM_COLS.containsKey(field)) {
            try { params.add(new BigDecimal(lit.asText())); } catch (NumberFormatException e) { return "FALSE"; }
            return "(" + NUM_COLS.get(field) + " " + sqlOp + " ?)";
        }
        if (lit.isNumber()) {
            // The expression appears twice, so its bind parameter (the JSON path) is added twice, in order.
            String x1 = textExpr(field, params), x2 = textExpr(field, params);
            params.add(lit.decimalValue());
            return "((CASE WHEN " + x1 + " ~ '^-?[0-9]+(\\.[0-9]+)?$' THEN (" + x2 + ")::numeric END) " + sqlOp + " ?)";
        }
        String x = textExpr(field, params);
        params.add(lit.asText());
        return "(" + x + " " + sqlOp + " ?)";
    }

    /** A column for canonical fields, otherwise a JSON path into the last event. Path segments are bind parameters. */
    private static String textExpr(String field, List<Object> params) {
        String col = TEXT_COLS.get(field);
        if (col != null) return col;
        if (field.equals("severity")) return "severity";
        if (NUM_COLS.containsKey(field)) return NUM_COLS.get(field) + "::text";
        String path = field.startsWith("raw.") ? field.substring(4) : field;
        StringBuilder sb = new StringBuilder("{");
        String[] segs = path.split("\\.");
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(segs[i].replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        params.add(sb.append('}').toString());
        return "(last_event #>> ?::text[])";
    }
}
