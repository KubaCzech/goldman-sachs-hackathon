package gs.alerting.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns any supported payload into events (JSON objects). One body may hold several events: a JSON array, NDJSON,
 * CSV rows, YAML documents, log lines. The format comes from the source's setting or the Content-Type; "auto" (the
 * default) detects it. Only bodies that are not text at all are rejected; any text becomes at least a
 * {"message": ...} event, so a source never loses data because of its format.
 */
public final class Formats {
    public static final List<String> ALL = List.of("auto", "json", "ndjson", "csv", "tsv", "yaml", "xml", "logfmt", "syslog", "form", "text");

    /** Result of parsing one body. */
    public static final class Parsed {
        public final String format;
        public final List<ObjectNode> events;
        Parsed(String format, List<ObjectNode> events) { this.format = format; this.events = events; }
    }

    public static final class NotText extends RuntimeException {
        NotText() { super("The body is not text (binary or invalid UTF-8)"); }
    }

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    /** Strict: "{..}\n{..}" is not one JSON document (the default reader silently ignores everything after the first). */
    private static final com.fasterxml.jackson.databind.ObjectReader STRICT =
            Json.M.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private Formats() {}

    /** Maps a Content-Type to a format, or null when it says nothing useful. */
    public static String fromContentType(String contentType) {
        if (contentType == null) return null;
        String ct = contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("x-ndjson") || ct.contains("jsonlines") || ct.contains("json-seq")) return "ndjson";
        if (ct.contains("json")) return "json";
        if (ct.contains("csv")) return "csv";
        if (ct.contains("tab-separated")) return "tsv";
        if (ct.contains("yaml")) return "yaml";
        if (ct.contains("xml")) return "xml";
        // curl -d and most HTTP clients label every body as a form unless told otherwise: not a useful hint.
        return null;   // text/plain, form-urlencoded and friends: let detection decide
    }

    public static Parsed parse(byte[] body, String format) {
        return parse(decode(body), format);
    }

    /**
     * The format a source is configured with is strict; a Content-Type is only a hint (senders mislabel bodies), so a
     * body that does not fit its Content-Type is detected instead.
     */
    public static Parsed parse(byte[] body, String sourceFormat, String contentType) {
        String text = decode(body);
        if (sourceFormat != null && !sourceFormat.isBlank() && !sourceFormat.equals("auto")) return parse(text, sourceFormat);
        String hint = fromContentType(contentType);
        if (hint != null) {
            try { return parse(text, hint); } catch (IllegalArgumentException mislabelled) { /* fall back to detection */ }
        }
        return parse(text, "auto");
    }

    public static Parsed parse(String text, String format) {
        String f = format == null || format.isBlank() ? "auto" : format.toLowerCase(Locale.ROOT);
        String t = text == null ? "" : stripBom(text).trim();
        if (t.isEmpty()) return new Parsed(f.equals("auto") ? "text" : f, List.of());
        if (!f.equals("auto")) return new Parsed(f, parseAs(t, f));
        char c = t.charAt(0);
        if (c == '{' || c == '[') {                 // the common case: parse once, no separate detection pass
            try { return new Parsed("json", objects(STRICT.readTree(t))); } catch (Exception notOneDocument) { /* NDJSON or not JSON */ }
            if (looksLikeNdjson(t)) return new Parsed("ndjson", ndjson(t));
        }
        String detected = detect(t);
        try {
            return new Parsed(detected, parseAs(t, detected));
        } catch (RuntimeException e) {
            return new Parsed("text", text(t));   // detection guessed wrong: never lose the data
        }
    }

    static String detect(String t) {
        char c = t.charAt(0);
        if (c == '{' || c == '[') {
            try { STRICT.readTree(t); return "json"; } catch (Exception ignore) { /* fall through */ }
            if (looksLikeNdjson(t)) return "ndjson";
        }
        if (c == '<' && !t.matches("(?s)^<\\d{1,3}>.*")) return "xml";
        String first = firstLine(t);
        if (first.matches("^<\\d{1,3}>.*")) return "syslog";
        if (t.startsWith("---") || looksLikeYaml(t)) return "yaml";
        if (looksDelimited(t, '\t')) return "tsv";
        for (char d : new char[]{',', ';', '|'}) if (looksDelimited(t, d)) return "csv";
        if (!t.contains("\n") && t.matches("^[\\w.\\-\\[\\]%]+=[^&\\s]*(&[\\w.\\-\\[\\]%]+=[^&\\s]*)+$")) return "form";
        if (looksLikeLogfmt(t)) return "logfmt";
        return "text";
    }

    private static List<ObjectNode> parseAs(String t, String f) {
        return switch (f) {
            case "json" -> json(t);
            case "ndjson" -> ndjson(t);
            case "csv" -> delimited(t, guessDelimiter(t));
            case "tsv" -> delimited(t, '\t');
            case "yaml" -> yaml(t);
            case "xml" -> xml(t);
            case "logfmt" -> logfmt(t);
            case "syslog" -> syslog(t);
            case "form" -> form(t);
            case "text" -> text(t);
            default -> throw new IllegalArgumentException("Unknown format '" + f + "'. Use one of " + ALL);
        };
    }

    // ------------------------------------------------------------------ JSON

    private static List<ObjectNode> json(String t) {
        JsonNode n;
        try { n = STRICT.readTree(t); } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("Not valid JSON: " + e.getOriginalMessage()); }
        return objects(n);
    }

    private static List<ObjectNode> ndjson(String t) {
        List<ObjectNode> out = new ArrayList<>();
        for (String line : t.split("\\r?\\n")) {
            if (line.isBlank()) continue;
            try { out.addAll(objects(Json.M.readTree(line))); }
            catch (Exception e) { out.add(message(line)); }        // one bad line does not sink the batch
        }
        return out;
    }

    /** An object is one event, an array is several; a bare value becomes {"value": ...}. */
    private static List<ObjectNode> objects(JsonNode n) {
        List<ObjectNode> out = new ArrayList<>();
        if (n == null || n.isNull()) return out;
        if (n.isObject()) out.add((ObjectNode) n);
        else if (n.isArray()) { for (JsonNode e : n) out.addAll(e.isObject() ? List.of((ObjectNode) e) : wrapValue(e)); }
        else out.addAll(wrapValue(n));
        return out;
    }

    private static List<ObjectNode> wrapValue(JsonNode v) {
        ObjectNode o = F.objectNode();
        o.set(v.isTextual() ? "message" : "value", v);
        return List.of(o);
    }

    private static boolean looksLikeNdjson(String t) {
        String[] lines = t.split("\\r?\\n");
        int objs = 0;
        for (String l : lines) {
            String s = l.trim();
            if (s.isEmpty()) continue;
            if (!(s.startsWith("{") && s.endsWith("}"))) return false;
            objs++;
        }
        return objs >= 2;   // callers have already tried the body as one JSON document
    }

    // ------------------------------------------------------------------ CSV / TSV

    private static char guessDelimiter(String t) {
        String first = firstLine(t);
        char best = ',';
        int bestCount = 0;
        for (char d : new char[]{',', ';', '\t', '|'}) {
            int c = splitDelimited(first, d).size();
            if (c > bestCount) { best = d; bestCount = c; }
        }
        return best;
    }

    /** At least 2 columns, a header row of names, and every row with the header's column count. */
    private static boolean looksDelimited(String t, char d) {
        List<String> lines = nonBlankLines(t);
        if (lines.size() < 2) return false;
        List<String> header = splitDelimited(lines.get(0), d);
        if (header.size() < 2) return false;
        for (String h : header) if (!h.trim().matches("[A-Za-z_@][\\w.@\\- ]*")) return false;
        for (int i = 1; i < lines.size(); i++) if (splitDelimited(lines.get(i), d).size() != header.size()) return false;
        return true;
    }

    private static List<ObjectNode> delimited(String t, char d) {
        List<String> lines = nonBlankLines(t);
        if (lines.isEmpty()) return List.of();
        List<String> header = splitDelimited(lines.get(0), d);
        List<ObjectNode> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> cells = splitDelimited(lines.get(i), d);
            ObjectNode o = F.objectNode();
            for (int c = 0; c < header.size(); c++) {
                String name = header.get(c).trim();
                if (name.isEmpty()) name = "column" + (c + 1);
                putPath(o, name, typed(c < cells.size() ? cells.get(c) : ""));
            }
            for (int c = header.size(); c < cells.size(); c++) o.set("column" + (c + 1), typed(cells.get(c)));
            out.add(o);
        }
        return out;
    }

    /** RFC 4180 quoting on one line ("a ""b""" -> a "b"). */
    static List<String> splitDelimited(String line, char d) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (ch == '"') q = false;
                else cur.append(ch);
            } else if (ch == '"' && cur.toString().isBlank()) { cur.setLength(0); q = true; }
            else if (ch == d) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    // ------------------------------------------------------------------ YAML

    private static boolean looksLikeYaml(String t) {
        List<String> lines = nonBlankLines(t);
        if (lines.isEmpty()) return false;
        int keyed = 0;
        for (String l : lines) {
            String s = l.trim();
            if (s.startsWith("#")) continue;
            if (s.matches("^-?\\s*[\\w.@\"'\\-]+\\s*:(\\s.*)?$") || s.startsWith("- ")) keyed++;
            else return false;
        }
        return keyed > 0;
    }

    private static List<ObjectNode> yaml(String t) {
        Yaml y = new Yaml(new SafeConstructor(new LoaderOptions()));
        List<ObjectNode> out = new ArrayList<>();
        for (Object doc : y.loadAll(t)) {
            if (doc == null) continue;
            JsonNode n = Json.M.valueToTree(normaliseYaml(doc));
            if (!n.isObject() && !n.isArray()) throw new IllegalArgumentException("YAML is not a mapping");
            out.addAll(objects(n));
        }
        return out;
    }

    /** SnakeYAML gives Dates and non-string keys; make them JSON friendly. */
    private static Object normaliseYaml(Object o) {
        if (o instanceof Map<?, ?> m) {
            java.util.LinkedHashMap<String, Object> r = new java.util.LinkedHashMap<>();
            m.forEach((k, v) -> r.put(String.valueOf(k), normaliseYaml(v)));
            return r;
        }
        if (o instanceof Collection<?> c) { List<Object> r = new ArrayList<>(); for (Object v : c) r.add(normaliseYaml(v)); return r; }
        if (o instanceof java.util.Date d) return d.toInstant().toString();
        return o;
    }

    // ------------------------------------------------------------------ XML

    private static List<ObjectNode> xml(String t) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            // No external entities or DTDs: payloads come from outside.
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setExpandEntityReferences(false);
            f.setXIncludeAware(false);
            Element root = f.newDocumentBuilder().parse(new ByteArrayInputStream(t.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
            // <events><event>..</event><event>..</event></events>: a root whose children all share one name is a batch.
            List<Element> kids = childElements(root);
            if (kids.size() > 1 && kids.stream().allMatch(k -> k.getTagName().equals(kids.get(0).getTagName())) && root.getAttributes().getLength() == 0) {
                List<ObjectNode> out = new ArrayList<>();
                for (Element k : kids) out.add(asObject(xmlValue(k)));
                return out;
            }
            return List.of(asObject(xmlValue(root)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Not valid XML: " + e.getMessage());
        }
    }

    private static ObjectNode asObject(JsonNode n) {
        if (n.isObject()) return (ObjectNode) n;
        ObjectNode o = F.objectNode();
        o.set("value", n);
        return o;
    }

    /** Attributes and child elements become fields; repeated children become arrays; text-only elements become values. */
    private static JsonNode xmlValue(Element e) {
        List<Element> kids = childElements(e);
        NamedNodeMap attrs = e.getAttributes();
        if (kids.isEmpty() && attrs.getLength() == 0) return typed(e.getTextContent().trim());
        ObjectNode o = F.objectNode();
        for (int i = 0; i < attrs.getLength(); i++) o.set(attrs.item(i).getNodeName(), typed(attrs.item(i).getNodeValue()));
        java.util.Set<String> repeated = new java.util.HashSet<>();
        for (Element k : kids) {
            String name = k.getTagName();
            JsonNode v = xmlValue(k);
            JsonNode existing = o.get(name);
            if (existing == null) o.set(name, v);
            else if (repeated.contains(name)) ((ArrayNode) existing).add(v);
            else { o.set(name, F.arrayNode().add(existing).add(v)); repeated.add(name); }
        }
        if (kids.isEmpty()) {
            String text = e.getTextContent().trim();
            if (!text.isEmpty()) o.set("value", typed(text));
        }
        return o;
    }

    private static List<Element> childElements(Element e) {
        List<Element> out = new ArrayList<>();
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) if (nl.item(i).getNodeType() == Node.ELEMENT_NODE) out.add((Element) nl.item(i));
        return out;
    }

    // ------------------------------------------------------------------ logfmt, form, syslog, text

    private static final Pattern KV = Pattern.compile("([\\w.@\\-\\[\\]]+)=(\"(?:[^\"\\\\]|\\\\.)*\"|\\S*)");

    private static boolean looksLikeLogfmt(String t) {
        List<String> lines = nonBlankLines(t);
        int good = 0;
        for (String l : lines) {
            Matcher m = KV.matcher(l);
            int n = 0;
            while (m.find()) n++;
            if (n >= 2) good++;
        }
        return good > 0 && good * 2 >= lines.size();
    }

    /** key=value pairs per line (logfmt); text that is not a pair is kept as "message". */
    private static List<ObjectNode> logfmt(String t) {
        List<ObjectNode> out = new ArrayList<>();
        for (String line : nonBlankLines(t)) {
            ObjectNode o = F.objectNode();
            Matcher m = KV.matcher(line);
            StringBuilder rest = new StringBuilder();
            int last = 0;
            while (m.find()) {
                rest.append(line, last, m.start());
                last = m.end();
                String v = m.group(2);
                if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
                putPath(o, m.group(1), typed(v));
            }
            rest.append(line.substring(last));
            String msg = rest.toString().trim();
            if (!msg.isEmpty() && !o.has("message") && !o.has("msg")) o.put("message", msg);
            if (!o.has("level")) guessLevel(o, line);
            out.add(o);
        }
        return out;
    }

    private static List<ObjectNode> form(String t) {
        ObjectNode o = F.objectNode();
        for (String pair : t.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            putPath(o, k, typed(v));
        }
        return List.of(o);
    }

    // RFC 5424: <PRI>VERSION TIMESTAMP HOST APP PROCID MSGID [SD] MSG ; RFC 3164: <PRI>Mmm dd hh:mm:ss HOST TAG[pid]: MSG
    private static final Pattern RFC5424 = Pattern.compile("^<(\\d{1,3})>1 (\\S+) (\\S+) (\\S+) (\\S+) (\\S+) (-|\\[.*?\\]) ?(.*)$");
    private static final Pattern RFC3164 = Pattern.compile("^<(\\d{1,3})>(\\w{3} [ \\d]\\d \\d\\d:\\d\\d:\\d\\d) (\\S+) ([^:\\[\\s]+)(?:\\[(\\d+)\\])?: ?(.*)$");
    private static final String[] SYSLOG_SEV = {"emergency", "alert", "critical", "error", "warning", "notice", "info", "debug"};

    private static List<ObjectNode> syslog(String t) {
        List<ObjectNode> out = new ArrayList<>();
        for (String line : nonBlankLines(t)) {
            ObjectNode o = F.objectNode();
            Matcher m5 = RFC5424.matcher(line), m3 = RFC3164.matcher(line);
            if (m5.matches()) {
                pri(o, m5.group(1));
                o.put("timestamp", m5.group(2)).put("host", m5.group(3)).put("app", m5.group(4));
                if (!"-".equals(m5.group(5))) o.put("procId", m5.group(5));
                if (!"-".equals(m5.group(6))) o.put("msgId", m5.group(6));
                o.put("message", m5.group(8));
            } else if (m3.matches()) {
                pri(o, m3.group(1));
                o.put("timestamp", m3.group(2)).put("host", m3.group(3)).put("app", m3.group(4));
                if (m3.group(5) != null) o.put("pid", Integer.parseInt(m3.group(5)));
                o.put("message", m3.group(6));
            } else {
                o.put("message", line);
                guessLevel(o, line);
            }
            out.add(o);
        }
        return out;
    }

    private static void pri(ObjectNode o, String pri) {
        int p = Integer.parseInt(pri);
        o.put("facility", p / 8).put("level", SYSLOG_SEV[p % 8]);
    }

    /** One event per line, {"message": line}, with the level guessed from words like ERROR or WARN. */
    private static List<ObjectNode> text(String t) {
        List<ObjectNode> out = new ArrayList<>();
        for (String line : nonBlankLines(t)) out.add(message(line));
        return out;
    }

    private static ObjectNode message(String line) {
        ObjectNode o = F.objectNode();
        o.put("message", line.trim());
        guessLevel(o, line);
        return o;
    }

    private static final Pattern LEVEL = Pattern.compile("\\b(FATAL|CRITICAL|CRIT|ERROR|ERR|SEVERE|WARNING|WARN|INFO|NOTICE|DEBUG|TRACE)\\b");

    private static void guessLevel(ObjectNode o, String line) {
        Matcher m = LEVEL.matcher(line.toUpperCase(Locale.ROOT));
        if (m.find()) o.put("level", m.group(1).toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ helpers

    /** "12" -> 12, "1.5" -> 1.5, "true" -> true, "" -> null, everything else stays text (leading zeros stay text). */
    static JsonNode typed(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("null")) return F.nullNode();
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) return F.booleanNode(Boolean.parseBoolean(s.toLowerCase(Locale.ROOT)));
        if (s.matches("-?(0|[1-9]\\d{0,17})")) return F.numberNode(Long.parseLong(s));
        if (s.matches("-?(0|[1-9]\\d*)\\.\\d+([eE][-+]?\\d+)?") && s.length() < 25) return F.numberNode(Double.parseDouble(s));
        return F.textNode(s);
    }

    /** "labels.service" in a header or key becomes a nested field, so rules use the same paths as for JSON. */
    private static void putPath(ObjectNode o, String path, JsonNode v) {
        String[] parts = path.split("\\.");
        ObjectNode cur = o;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode next = cur.get(parts[i]);
            if (next == null || !next.isObject()) { next = F.objectNode(); cur.set(parts[i], next); }
            cur = (ObjectNode) next;
        }
        cur.set(parts[parts.length - 1], v);
    }

    private static List<String> nonBlankLines(String t) {
        List<String> out = new ArrayList<>();
        for (String l : t.split("\\r?\\n")) if (!l.isBlank()) out.add(l);
        return out;
    }

    private static String firstLine(String t) {
        int i = t.indexOf('\n');
        return (i < 0 ? t : t.substring(0, i)).trim();
    }

    private static String stripBom(String s) { return s.startsWith("\uFEFF") ? s.substring(1) : s; }

    /** Strict UTF-8; binary payloads are refused (they go to the dead-letter topic). */
    static String decode(byte[] body) {
        if (body == null) return "";
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            long control = s.chars().filter(ch -> ch < 0x20 && ch != '\n' && ch != '\r' && ch != '\t').count();
            if (control > Math.max(2, s.length() / 100)) throw new NotText();
            return s;
        } catch (CharacterCodingException e) {
            throw new NotText();
        }
    }
}
