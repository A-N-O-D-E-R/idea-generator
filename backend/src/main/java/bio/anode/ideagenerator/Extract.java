package bio.anode.ideagenerator;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Seeds from a link (upstream extract.ts): fetch the page and pull out readable
 * text. Deliberately simple: prefer article, then main, then body; drop
 * scripts, navigation and other chrome; keep paragraph breaks.
 */
public final class Extract {
    private Extract() {}

    static final int MAX_BYTES = 2_000_000;
    static final int MAX_CHARS = 6000;

    public record Page(String title, String text) {}

    private static final Pattern HAS_WS = Pattern.compile(Js.WS);
    private static final Pattern HTTP = Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern WWW = Pattern.compile("^www\\.[^\\s.]+\\.\\S+$", Pattern.CASE_INSENSITIVE);

    /** A single URL (optionally without scheme), or null if the text is not one. */
    public static String asUrl(String text) {
        String t = Js.trim(text);
        if (HAS_WS.matcher(t).find()) return null;
        String candidate = HTTP.matcher(t).matches() ? t : WWW.matcher(t).matches() ? "https://" + t : null;
        if (candidate == null) return null;
        try {
            URI u = new URI(escape(candidate));
            String scheme = u.getScheme().toLowerCase(Locale.ROOT);
            if (u.getHost() == null || !(scheme.equals("http") || scheme.equals("https"))) return null;
            int port = u.getPort();
            boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
            // WHATWG URL serialisation: lower-case scheme and host, default port dropped, "/" for an empty path.
            return scheme + "://" + (u.getRawUserInfo() != null ? u.getRawUserInfo() + "@" : "") + u.getHost().toLowerCase(Locale.ROOT)
                    + (defaultPort ? "" : ":" + port)
                    + (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath())
                    + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "")
                    + (u.getRawFragment() != null ? "#" + u.getRawFragment() : "");
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /** Percent-encode what a browser would before java.net.URI sees it. */
    private static String escape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean badPercent = c == '%' && !(i + 2 < s.length() && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2)));
            if (c > 0x7e || c < 0x21 || "\"<>\\^`{|}".indexOf(c) >= 0 || badPercent) {
                int cp = s.codePointAt(i);
                if (Character.charCount(cp) == 2) i++;
                for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) out.append(String.format("%%%02X", b));
            } else out.append(c);
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }

    static final Map<String, String> ENTITIES = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("quot", "\""), Map.entry("apos", "'"),
            Map.entry("nbsp", " "), Map.entry("ndash", "–"), Map.entry("mdash", "—"), Map.entry("hellip", "…"),
            Map.entry("laquo", "«"), Map.entry("raquo", "»"), Map.entry("rsquo", "’"), Map.entry("lsquo", "‘"),
            Map.entry("rdquo", "”"), Map.entry("ldquo", "“"), Map.entry("aring", "å"), Map.entry("auml", "ä"),
            Map.entry("ouml", "ö"), Map.entry("Aring", "Å"), Map.entry("Auml", "Ä"), Map.entry("Ouml", "Ö"), Map.entry("eacute", "é"));

    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-f]+|#\\d+|[a-z]+);", Pattern.CASE_INSENSITIVE);

    public static String decodeEntities(String s) {
        return ENTITY.matcher(s).replaceAll(m -> Matcher.quoteReplacement(entity(m.group(0), m.group(1))));
    }

    private static String entity(String whole, String e) {
        if (e.charAt(0) == '#') {
            try {
                boolean hex = e.charAt(1) == 'x' || e.charAt(1) == 'X';
                long code = Long.parseLong(hex ? e.substring(2) : e.substring(1), hex ? 16 : 10);
                return code > 0 && code < 0x110000 ? Character.toString((int) code) : whole;
            } catch (NumberFormatException tooBig) {
                return whole;
            }
        }
        String v = ENTITIES.get(e);
        if (v == null) v = ENTITIES.get(e.toLowerCase(Locale.ROOT));
        return v != null ? v : whole;
    }

    private static final int CI = Pattern.CASE_INSENSITIVE;
    private static final Pattern OG_TITLE = Pattern.compile("<meta[^>]+property=[\"']og:title[\"'][^>]*content=[\"']([^\"']+)[\"']", CI);
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>([\\s\\S]*?)</title>", CI);
    private static final Pattern COMMENT = Pattern.compile("<!--[\\s\\S]*?-->");
    private static final String[] CHROME = {"script", "style", "noscript", "svg", "nav", "header", "footer", "aside", "form", "template", "iframe"};

    private static String pick(String body, String tag) {
        Matcher m = Pattern.compile("<" + tag + "\\b[^>]*>([\\s\\S]*?)</" + tag + ">", CI).matcher(body);
        return m.find() ? m.group(1) : null;
    }

    public static Page htmlToText(String html) {
        Matcher tm = OG_TITLE.matcher(html);
        if (!tm.find()) tm = TITLE.matcher(html);
        String title = tm.find(0) ? Js.trim(decodeEntities(tm.group(1)).replaceAll(Js.WS + "+", " ")) : "";

        String body = COMMENT.matcher(html).replaceAll("");
        for (String tag : CHROME) body = Pattern.compile("<" + tag + "\\b[\\s\\S]*?</" + tag + ">", CI).matcher(body).replaceAll(" ");
        String main = pick(body, "article");
        if (main == null) main = pick(body, "main");
        if (main == null) main = pick(body, "body");
        if (main == null) main = body;

        String text = decodeEntities(main
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|h[1-6]|li|blockquote|section|tr)>", "\n\n")
                .replaceAll("<[^>]+>", " "));
        text = Js.trim(Arrays.stream(text.split("\n", -1))
                .map(l -> Js.trim(l.replaceAll("[ \\t\\u00a0]+", " ")))
                .collect(Collectors.joining("\n"))
                .replaceAll("\n{3,}", "\n\n"));
        return new Page(title, text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) + "\n[…]" : text);
    }

    static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(12)).build();

    public static Page fetchPage(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "sv,en;q=0.8")
                    .GET().build();
            HttpResponse<InputStream> res = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = res.body()) {
                if (res.statusCode() < 200 || res.statusCode() > 299) throw new RuntimeException("HTTP " + res.statusCode());
                String type = res.headers().firstValue("content-type").orElse("");
                if (!Pattern.compile("html|text/plain", CI).matcher(type).find()) {
                    throw new RuntimeException("Not a web page (" + (type.isEmpty() ? "unknown type" : type) + ")");
                }
                String html = new String(in.readNBytes(MAX_BYTES), StandardCharsets.UTF_8);
                return Pattern.compile("text/plain", CI).matcher(type).find() ? new Page("", Js.cut(html, MAX_CHARS)) : htmlToText(html);
            }
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage() != null ? e.getMessage() : "fetch failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted", e);
        }
    }
}
