package gs.alerting.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Recursive-descent parser for the WHERE-clause subset (SPEC 6.2). Produces the filter AST. */
public final class SqlParser {
    public static final class ParseException extends IllegalArgumentException {
        public final int position;
        public ParseException(String msg, int position) { super(msg + " (at position " + position + ")"); this.position = position; }
    }

    private enum T { IDENT, STRING, NUMBER, OP, LPAREN, RPAREN, COMMA, EOF }
    private record Tok(T type, String text, int pos, boolean quoted) {}

    private final List<Tok> toks = new ArrayList<>();
    private int i = 0;

    private SqlParser(String sql) { tokenize(sql); }

    public static ObjectNode parse(String sql) {
        if (sql == null || sql.isBlank()) return Filter.alwaysTrue();
        SqlParser p = new SqlParser(sql);
        if (p.isKw("where")) p.i++;
        if (p.peek().type == T.EOF) return Filter.alwaysTrue();
        JsonNode n = p.or();
        if (p.peek().type != T.EOF) throw new ParseException("Unexpected '" + p.peek().text + "'", p.peek().pos);
        return (ObjectNode) n;
    }

    private void tokenize(String s) {
        int n = s.length(), p = 0;
        while (p < n) {
            char c = s.charAt(p);
            if (Character.isWhitespace(c)) { p++; continue; }
            int start = p;
            if (c == '(') { toks.add(new Tok(T.LPAREN, "(", p++, false)); continue; }
            if (c == ')') { toks.add(new Tok(T.RPAREN, ")", p++, false)); continue; }
            if (c == ',') { toks.add(new Tok(T.COMMA, ",", p++, false)); continue; }
            if (c == '\'') {
                StringBuilder sb = new StringBuilder();
                p++;
                boolean closed = false;
                while (p < n) {
                    char d = s.charAt(p);
                    if (d == '\'') {
                        if (p + 1 < n && s.charAt(p + 1) == '\'') { sb.append('\''); p += 2; continue; }
                        closed = true; p++; break;
                    }
                    sb.append(d); p++;
                }
                if (!closed) throw new ParseException("Unterminated string", start);
                toks.add(new Tok(T.STRING, sb.toString(), start, true));
                continue;
            }
            if (c == '"') {
                int end = s.indexOf('"', p + 1);
                if (end < 0) throw new ParseException("Unterminated quoted field", start);
                toks.add(new Tok(T.IDENT, s.substring(p + 1, end), start, true));
                p = end + 1;
                continue;
            }
            if (c == '=' ) { toks.add(new Tok(T.OP, "=", p++, false)); continue; }
            if (c == '!' && p + 1 < n && s.charAt(p + 1) == '=') { toks.add(new Tok(T.OP, "!=", p, false)); p += 2; continue; }
            if (c == '<') {
                if (p + 1 < n && s.charAt(p + 1) == '>') { toks.add(new Tok(T.OP, "!=", p, false)); p += 2; }
                else if (p + 1 < n && s.charAt(p + 1) == '=') { toks.add(new Tok(T.OP, "<=", p, false)); p += 2; }
                else toks.add(new Tok(T.OP, "<", p++, false));
                continue;
            }
            if (c == '>') {
                if (p + 1 < n && s.charAt(p + 1) == '=') { toks.add(new Tok(T.OP, ">=", p, false)); p += 2; }
                else toks.add(new Tok(T.OP, ">", p++, false));
                continue;
            }
            if (Character.isDigit(c) || ((c == '-' || c == '.') && p + 1 < n && (Character.isDigit(s.charAt(p + 1)) || s.charAt(p + 1) == '.'))) {
                p++;
                while (p < n && (Character.isDigit(s.charAt(p)) || s.charAt(p) == '.' || s.charAt(p) == 'e' || s.charAt(p) == 'E')) p++;
                String t = s.substring(start, p);
                try { new BigDecimal(t); } catch (NumberFormatException e) { throw new ParseException("Invalid number '" + t + "'", start); }
                toks.add(new Tok(T.NUMBER, t, start, false));
                continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '@') {
                p++;
                while (p < n) {
                    char d = s.charAt(p);
                    if (Character.isLetterOrDigit(d) || d == '_' || d == '@' || d == '-' || d == '.') p++; else break;
                }
                toks.add(new Tok(T.IDENT, s.substring(start, p), start, false));
                continue;
            }
            throw new ParseException("Unexpected character '" + c + "'", p);
        }
        toks.add(new Tok(T.EOF, "end of input", n, false));
    }

    private Tok peek() { return toks.get(i); }
    private Tok next() { return toks.get(i++); }

    private boolean isKw(String kw) {
        Tok t = peek();
        return t.type == T.IDENT && !t.quoted && t.text.equalsIgnoreCase(kw);
    }

    private void expectKw(String kw) {
        if (!isKw(kw)) throw new ParseException("Expected " + kw.toUpperCase() + " but found '" + peek().text + "'", peek().pos);
        i++;
    }

    private JsonNode or() {
        List<JsonNode> args = new ArrayList<>();
        args.add(and());
        while (isKw("or")) { i++; args.add(and()); }
        return args.size() == 1 ? args.get(0) : Filter.group("or", args);
    }

    private JsonNode and() {
        List<JsonNode> args = new ArrayList<>();
        args.add(unary());
        while (isKw("and")) { i++; args.add(unary()); }
        return args.size() == 1 ? args.get(0) : Filter.group("and", args);
    }

    private JsonNode unary() {
        if (isKw("not")) { i++; return Filter.not(unary()); }
        if (peek().type == T.LPAREN) {
            i++;
            JsonNode n = or();
            if (peek().type != T.RPAREN) throw new ParseException("Expected ')' but found '" + peek().text + "'", peek().pos);
            i++;
            return n;
        }
        return predicate();
    }

    private JsonNode predicate() {
        Tok f = next();
        if (f.type != T.IDENT) throw new ParseException("Expected a field name but found '" + f.text + "'", f.pos);
        String field = f.text;
        Tok t = peek();
        if (t.type == T.OP) {
            i++;
            String op = switch (t.text) { case "=" -> "eq"; case "!=" -> "ne"; case ">" -> "gt"; case ">=" -> "ge"; case "<" -> "lt"; default -> "le"; };
            return Filter.leaf(op, field, literal());
        }
        if (isKw("is")) {
            i++;
            boolean neg = false;
            if (isKw("not")) { i++; neg = true; }
            expectKw("null");
            return Filter.leaf(neg ? "notnull" : "isnull", field, null);
        }
        boolean neg = false;
        if (isKw("not")) { i++; neg = true; }
        JsonNode node;
        if (isKw("in")) {
            i++;
            if (peek().type != T.LPAREN) throw new ParseException("Expected '(' after IN", peek().pos);
            i++;
            ObjectNode o = Json.obj();
            o.put("op", "in").put("field", field);
            ArrayNode vals = o.putArray("values");
            vals.add(literal());
            while (peek().type == T.COMMA) { i++; vals.add(literal()); }
            if (peek().type != T.RPAREN) throw new ParseException("Expected ')' to close IN list", peek().pos);
            i++;
            node = o;
        } else if (isKw("between")) {
            i++;
            ObjectNode o = Json.obj();
            o.put("op", "between").put("field", field);
            o.set("low", literal());
            expectKw("and");
            o.set("high", literal());
            node = o;
        } else if (isKw("like")) {
            i++;
            Tok s = next();
            if (s.type != T.STRING) throw new ParseException("LIKE needs a quoted pattern", s.pos);
            node = Filter.leaf("like", field, TextNode.valueOf(s.text));
        } else {
            throw new ParseException("Expected a comparison after '" + field + "' but found '" + t.text + "'", t.pos);
        }
        return neg ? Filter.not(node) : node;
    }

    private JsonNode literal() {
        Tok t = next();
        switch (t.type) {
            case STRING: return TextNode.valueOf(t.text);
            case NUMBER: {
                BigDecimal d = new BigDecimal(t.text);
                if (d.scale() <= 0 && d.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) < 0) return LongNode.valueOf(d.longValueExact());
                return DecimalNode.valueOf(d);
            }
            case IDENT:
                if (!t.quoted && t.text.equalsIgnoreCase("true")) return BooleanNode.TRUE;
                if (!t.quoted && t.text.equalsIgnoreCase("false")) return BooleanNode.FALSE;
                throw new ParseException("Expected a value but found '" + t.text + "' (text values need single quotes)", t.pos);
            default:
                throw new ParseException("Expected a value but found '" + t.text + "'", t.pos);
        }
    }
}
