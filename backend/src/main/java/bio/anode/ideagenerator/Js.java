package bio.anode.ideagenerator;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON plus the handful of JavaScript semantics the upstream server relies on
 * (String(), Number(), trim(), slice()), so untrusted input is coerced the same way.
 */
@UtilityClass
public class Js {
    public static final JsonMapper M = JsonMapper.builder().build();

    public static JsonNode parse(String s) {
        return M.readTree(s);
    }

    public static String stringify(Object o) {
        return M.writeValueAsString(o);
    }

    public static ObjectNode tree(Object o) {
        return (ObjectNode) M.valueToTree(o);
    }

    public static ObjectNode object() {
        return M.createObjectNode();
    }

    /** An ordered map from key/value pairs; null values stay (they serialise as JSON null). */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // JS \s and String.prototype.trim: Unicode white space plus the BOM.
    static final String WS = "[\\p{IsWhite_Space}\\uFEFF]";
    private static final Pattern TRIM = Pattern.compile("^" + WS + "+|" + WS + "+$");

    public static String trim(String s) {
        return TRIM.matcher(s).replaceAll("");
    }

    /** s.slice(0, max) */
    public static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    public static boolean absent(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull();
    }

    /** String(v ?? '') */
    public static String str(JsonNode n) {
        if (absent(n)) return "";
        if (n.isString()) return n.stringValue();
        if (n.isNumber()) return numberText(n.asDouble());
        if (n.isBoolean()) return String.valueOf(n.booleanValue());
        if (n.isObject()) return "[object Object]";
        List<String> parts = new ArrayList<>();
        for (JsonNode e : n.values()) parts.add(str(e));
        return String.join(",", parts);
    }

    /** typeof v === 'string' ? v.trim().slice(0, max) : '' (app.ts str()). */
    public static String str(JsonNode n, int max) {
        return n != null && n.isString() ? cut(trim(n.stringValue()), max) : "";
    }

    public static String numberText(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e21) return Long.toString((long) d);
        return Double.toString(d);
    }

    private static final Pattern NUMERIC =
            Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?|[+-]?Infinity");

    /** Number(s) for a string. */
    public static double num(String s) {
        String t = trim(s);
        if (t.isEmpty()) return 0;
        if (NUMERIC.matcher(t).matches()) return Double.parseDouble(t);
        if (t.matches("0[xX][0-9a-fA-F]+")) return Long.parseLong(t.substring(2), 16);
        return Double.NaN;
    }

    /** Number(v): missing is NaN, null is 0, booleans 0/1, strings parsed. */
    public static double num(JsonNode n) {
        if (n == null || n.isMissingNode()) return Double.NaN;
        if (n.isNull()) return 0;
        if (n.isNumber()) return n.asDouble();
        if (n.isBoolean()) return n.booleanValue() ? 1 : 0;
        if (n.isString()) return num(n.stringValue());
        if (n.isArray() && n.isEmpty()) return 0;
        if (n.isArray() && n.size() == 1) return num(n.get(0));
        return Double.NaN;
    }

    /** Number.isFinite(v): no coercion. */
    public static boolean isFinite(JsonNode n) {
        return n != null && n.isNumber() && Double.isFinite(n.asDouble());
    }

    /** Number.isInteger(v): no coercion; 3.0 counts. */
    public static boolean isInt(JsonNode n) {
        return isFinite(n) && n.asDouble() == Math.rint(n.asDouble());
    }

    /** Array.isArray(v) ? v : [] */
    public static List<JsonNode> arr(JsonNode n) {
        return n != null && n.isArray() ? new ArrayList<>(n.values()) : List.of();
    }

    /** new Date().toISOString() */
    public static String isoNow() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));
    }

    /** new Date().toISOString().slice(0, 10) */
    public static String utcToday() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }
}
