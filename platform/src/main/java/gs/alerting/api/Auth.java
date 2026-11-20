package gs.alerting.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gs.alerting.core.Json;
import gs.alerting.util.Env;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mock login with real HS256 JWTs, and the caller's role model (SPEC 10.1). */
public final class Auth {
    private static final byte[] SECRET = Env.get("JWT_SECRET", "dev-secret-change-me").getBytes(StandardCharsets.UTF_8);
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    public static final class ApiError extends RuntimeException {
        public final int status;
        public ApiError(int status, String message) { super(message); this.status = status; }
    }

    public static final class Caller {
        public String userId;
        public ObjectNode user;
        public boolean admin;
        public final Map<String, String> roles = new LinkedHashMap<>();   // teamId -> admin|member
        public final Map<String, String> teamNames = new LinkedHashMap<>();

        public boolean member(String teamId) { return admin || (teamId != null && roles.containsKey(teamId)); }
        public boolean teamAdmin(String teamId) { return admin || (teamId != null && "admin".equals(roles.get(teamId))); }
        public List<String> teamIds() { return new ArrayList<>(roles.keySet()); }

        public void requireMember(String teamId) { if (!member(teamId)) deny("You are not a member of team '" + teamId + "'"); }
        public void requireTeamAdmin(String teamId) { if (!teamAdmin(teamId)) deny("Only an admin of team '" + teamId + "' can do this"); }
        public void requirePlatformAdmin() { if (!admin) deny("Only a platform admin can do this"); }

        private void deny(String msg) {
            Store.audit(userId, "ACCESS_DENIED", "access", null, null, Json.obj().put("reason", msg));
            throw new ApiError(403, msg);
        }

        public ObjectNode toJson() {
            ObjectNode o = user.deepCopy();
            ArrayNode t = o.putArray("teams");
            roles.forEach((id, role) -> t.addObject().put("teamId", id).put("teamName", teamNames.get(id)).put("role", role));
            return o;
        }
    }

    private Auth() {}

    private static String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
            return ENC.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static String issue(String userId) {
        String header = ENC.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        ObjectNode claims = Json.obj().put("sub", userId).put("exp", System.currentTimeMillis() / 1000 + 12 * 3600);
        String body = ENC.encodeToString(Json.str(claims).getBytes(StandardCharsets.UTF_8));
        return header + "." + body + "." + sign(header + "." + body);
    }

    /** Returns the user id, or throws 401. */
    public static String verify(String token) {
        try {
            String[] p = token.split("\\.");
            if (p.length != 3) throw new IllegalArgumentException();
            if (!MessageDigest.isEqual(sign(p[0] + "." + p[1]).getBytes(StandardCharsets.UTF_8), p[2].getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException();
            JsonNode claims = Json.parse(new String(DEC.decode(p[1]), StandardCharsets.UTF_8));
            if (claims.path("exp").asLong() < System.currentTimeMillis() / 1000) throw new IllegalArgumentException();
            return claims.get("sub").asText();
        } catch (Exception e) {
            throw new ApiError(401, "Please sign in again");
        }
    }

    public static Caller load(String userId) {
        ObjectNode user = Store.get("user", userId);
        if (user == null) throw new ApiError(401, "Unknown user");
        Caller c = new Caller();
        c.userId = userId;
        c.user = user;
        c.admin = user.path("platformAdmin").asBoolean(false);
        for (ObjectNode team : Store.list("team")) {
            String tid = Json.text(team, "id");
            String role = null;
            for (JsonNode m : team.path("members")) if (userId.equals(Json.text(m, "userId"))) role = Json.text(m, "role", "member");
            if (role == null && c.admin) role = "admin";
            if (role != null) { c.roles.put(tid, role); c.teamNames.put(tid, Json.text(team, "name", tid)); }
        }
        return c;
    }
}
