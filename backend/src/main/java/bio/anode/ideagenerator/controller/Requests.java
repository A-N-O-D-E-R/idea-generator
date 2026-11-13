package bio.anode.ideagenerator.controller;

import bio.anode.ideagenerator.AppError;
import bio.anode.ideagenerator.Js;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Bodies are read as raw JSON and coerced the way upstream does (app.ts), so the contract, including what bad input
 * turns into, stays the same.
 */
final class Requests {
    private Requests() {}

    static final MediaType TEXT_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);
    static final MediaType MARKDOWN = MediaType.parseMediaType("text/markdown; charset=utf-8");

    static JsonNode body(byte[] raw) {
        if (raw == null || raw.length == 0) return Js.object();
        if (raw.length > 1_000_000) throw new AppError(413, "too_large", "Request body too large");
        try {
            JsonNode n = Js.M.readTree(raw);
            if (n == null || n.isMissingNode()) throw new AppError(400, "bad_json", "Invalid JSON");
            return n;
        } catch (JacksonException e) {
            throw new AppError(400, "bad_json", "Invalid JSON");
        }
    }

    /** Number(v) || 30, clamped to 1..365 (days query and body). */
    static int days(double v) {
        double d = Double.isNaN(v) || v == 0 ? 30 : v;
        return (int) Math.min(365, Math.max(1, d));
    }

    /** The ?days= query parameter. */
    static int days(String v) {
        return days(v == null ? 0 : Js.num(v));
    }

    static String lang(String v) {
        return "en".equals(v) ? "en" : "sv";
    }

    /** body.lang, if it is a string. */
    static String lang(JsonNode v) {
        return lang(v.isString() ? v.stringValue() : null);
    }

    static Long longOrNull(JsonNode n) {
        return Js.isInt(n) ? n.asLong() : null;
    }
}
