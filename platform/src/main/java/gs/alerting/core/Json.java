package gs.alerting.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** JSON helpers, dotted-path resolution and hashing. */
public final class Json {
    public static final ObjectMapper M = new ObjectMapper();
    private static final Map<String, String[]> SPLIT = new ConcurrentHashMap<>();

    private Json() {}

    public static ObjectNode obj() { return M.createObjectNode(); }
    public static ArrayNode arr() { return M.createArrayNode(); }

    public static JsonNode parse(String s) {
        try { return M.readTree(s); } catch (Exception e) { throw new IllegalArgumentException("Invalid JSON: " + e.getMessage()); }
    }

    public static JsonNode parse(byte[] b) throws java.io.IOException { return M.readTree(b); }

    public static String str(JsonNode n) {
        try { return M.writeValueAsString(n); } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Resolve a dotted path inside a JSON tree. Returns null when missing or JSON null. */
    public static JsonNode path(JsonNode root, String path) {
        if (root == null || path == null) return null;
        String[] parts = SPLIT.computeIfAbsent(path, p -> p.split("\\."));
        JsonNode cur = root;
        for (String p : parts) {
            if (cur == null) return null;
            if (cur.isObject()) cur = cur.get(p);
            else if (cur.isArray()) {
                try { cur = cur.get(Integer.parseInt(p)); } catch (NumberFormatException e) { return null; }
            } else return null;
        }
        return (cur == null || cur.isNull() || cur.isMissingNode()) ? null : cur;
    }

    /** Text form of a value: strings unquoted, containers as compact JSON. */
    public static String text(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isValueNode()) return n.asText();
        return str(n);
    }

    public static String text(JsonNode root, String field) { return text(root == null ? null : root.get(field)); }

    public static String text(JsonNode root, String field, String dflt) {
        String s = text(root, field);
        return s == null || s.isEmpty() ? dflt : s;
    }

    public static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) arr.forEach(x -> { if (!x.isNull()) out.add(x.asText()); });
        return out;
    }

    public static String sha1Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
