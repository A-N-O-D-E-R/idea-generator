package bio.anode.ideagenerator;

import bio.anode.ideagenerator.Providers.Chat;
import bio.anode.ideagenerator.Providers.ChatRequest;
import bio.anode.ideagenerator.Providers.ChatResult;
import bio.anode.ideagenerator.Repo.LeadDraft;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The lead panel (upstream leads.ts): fresh material to seed a board with.
 *
 * X trends: Grok with OpenRouter's native web search. A lead is only kept if
 * its URL is among the citations the SEARCH ENGINE returned, never a URL the
 * model wrote. AI research: Hugging Face's free paper APIs; an LLM picks the
 * six most useful for the goal and writes a hook for each.
 */
public final class Leads {
    private Leads() {}

    public record Paper(String id, String title, String summary, String published, int upvotes) {}

    public record Found(List<LeadDraft> leads, int dropped) {}

    static final Map<String, String> LANG_NAME = Map.of("sv", "idiomatic Swedish", "en", "English");
    static final String DEFAULT_TOPIC = "new technology, AI tools, and new ways people actually use them";

    static final String LEAD_PROPS = """
            "title":{"type":"string","description":"Short title, 2–8 words."},
            "summary":{"type":"string","description":"What is happening, 1–2 sentences, at most 40 words."},
            "hook":{"type":"string","description":"One sentence: a new idea this could inspire for the goal."}""";
    static final JsonNode X_SCHEMA = Js.parse("""
            {"type":"object","properties":{"leads":{"type":"array","items":{"type":"object","properties":{%s,
              "url":{"type":"string","description":"The x.com URL of one real post you found."}},
              "required":["title","summary","hook","url"],"additionalProperties":false}}},
             "required":["leads"],"additionalProperties":false}""".formatted(LEAD_PROPS));
    static final JsonNode RESEARCH_SCHEMA = Js.parse("""
            {"type":"object","properties":{"leads":{"type":"array","items":{"type":"object","properties":{
              "id":{"type":"string","description":"The paper id exactly as given in brackets."},%s},
              "required":["id","title","summary","hook"],"additionalProperties":false}}},
             "required":["leads"],"additionalProperties":false}""".formatted(LEAD_PROPS));

    private static String clean(JsonNode v, int max) {
        return Js.cut(Js.trim(Js.str(v)), max);
    }

    /** Compare URLs loosely: host without www, twitter.com = x.com, no query or trailing slash. */
    public static String normalizeUrl(String u) {
        try {
            URI x = new URI(u);
            if (x.getScheme() == null || x.getHost() == null) return null;
            String host = x.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "").replaceFirst("^(mobile\\.)?twitter\\.com$", "x.com");
            String path = x.getRawPath() == null || x.getRawPath().isEmpty() ? "/" : x.getRawPath();
            return host + path.replaceAll("/+$", "");
        } catch (Exception e) {
            return null;
        }
    }

    private static final Pattern X_URL = Pattern.compile("^https?://(www\\.|mobile\\.)?(x|twitter)\\.com/.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public static Llm.Out<Found> xLeads(Chat chat, String model, String goal, String lang) {
        String topic = Js.trim(goal).isEmpty() ? DEFAULT_TOPIC : Js.trim(goal);
        String user = String.join("\n\n",
                "Search X for posts from the last 7 days that show emerging trends, new tools, behaviours or debates relevant to this goal:\n\"" + topic + "\"",
                "Find 6 distinct trends. Each must come from a real post you found in the search results; give that post's x.com URL.",
                "Write title, summary and hook in " + LANG_NAME.get(lang) + ".");
        ChatResult r = chat.chat(new ChatRequest(model,
                "You are a trend scout. You only report what you actually found in the search results, never from memory.",
                user, "leads", X_SCHEMA, 0.3, 3000, Js.parse("[{\"id\":\"web\",\"engine\":\"native\",\"max_results\":12}]")));
        Set<String> cited = new HashSet<>();
        for (String c : r.citations() == null ? List.<String>of() : r.citations()) {
            String n = normalizeUrl(c);
            if (n != null) cited.add(n);
        }
        List<LeadDraft> all = Js.arr(r.value().path("leads")).stream().map(l -> new LeadDraft(
                clean(l.path("title"), 120), clean(l.path("summary"), 400), clean(l.path("hook"), 300), clean(l.path("url"), 500),
                new LinkedHashMap<>())).toList();
        List<LeadDraft> leads = all.stream().filter(l -> !l.title().isEmpty() && X_URL.matcher(l.url()).matches()
                && cited.contains(normalizeUrl(l.url()))).toList();
        return new Llm.Out<>(new Found(leads, all.size() - leads.size()), r.costUsd());
    }

    private static String ws(String s) {
        return Js.trim(s.replaceAll(Js.WS + "+", " "));
    }

    private static String truthy(JsonNode... options) {
        for (JsonNode o : options) {
            String s = Js.str(o);
            if (!Js.absent(o) && !s.isEmpty() && !(o.isBoolean() && !o.booleanValue()) && !(o.isNumber() && o.asDouble() == 0)) return s;
        }
        return "";
    }

    static Paper toPaper(JsonNode raw) {
        JsonNode p = Js.absent(raw.path("paper")) ? raw : raw.path("paper");
        if (truthy(p.path("id")).isEmpty() || truthy(p.path("title")).isEmpty()) return null;
        double up = Js.num(p.path("upvotes"));
        return new Paper(Js.str(p.path("id")), ws(Js.str(p.path("title"))),
                ws(truthy(p.path("ai_summary"), p.path("summary"), raw.path("summary"))),
                truthy(p.path("publishedAt"), raw.path("publishedAt")),
                Double.isFinite(up) ? (int) up : 0);
    }

    /** Date.parse(): epoch millis, or null when the string is not a date. */
    static Long parseDate(String s) {
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return java.time.LocalDateTime.parse(s).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** encodeURIComponent */
    static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("%21", "!").replace("%27", "'")
                .replace("%28", "(").replace("%29", ")").replace("%7E", "~");
    }

    /** The real Hugging Face fetch: body text, or an exception for a non-2xx answer. */
    static String hfGet(String url) {
        try {
            HttpResponse<String> res = Providers.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET(),
                    Map.of("Accept", "application/json"));
            if (res.statusCode() < 200 || res.statusCode() > 299) throw new RuntimeException("Hugging Face " + res.statusCode());
            return res.body();
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /** Hugging Face paper search for one query, any date: prior work for a single idea. */
    public static List<Paper> searchPapers(String query) {
        return searchPapers(query, Leads::hfGet);
    }

    public static List<Paper> searchPapers(String query, Function<String, String> fetch) {
        String q = Js.trim(query);
        if (q.isEmpty()) return List.of();
        try {
            return Js.arr(Js.parse(fetch.apply("https://huggingface.co/api/papers/search?q=" + encode(Js.cut(q, 200)) + "&limit=10")))
                    .stream().map(Leads::toPaper).filter(Objects::nonNull).toList();
        } catch (RuntimeException e) {
            return List.of(); // prior work is a bonus: no search, no section
        }
    }

    public static List<Paper> fetchPapers(String goal) {
        return fetchPapers(goal, Leads::hfGet);
    }

    public static List<Paper> fetchPapers(String goal, Function<String, String> fetch) {
        Function<String, List<JsonNode>> get = url -> {
            try {
                return Js.arr(Js.parse(fetch.apply(url)));
            } catch (RuntimeException e) {
                return List.of();
            }
        };
        String g = Js.trim(goal);
        List<JsonNode> daily, searched;
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<List<JsonNode>> d = CompletableFuture.supplyAsync(() -> get.apply("https://huggingface.co/api/daily_papers?limit=60"), ex);
            CompletableFuture<List<JsonNode>> s = g.isEmpty() ? CompletableFuture.completedFuture(List.of())
                    : CompletableFuture.supplyAsync(() -> get.apply("https://huggingface.co/api/papers/search?q=" + encode(Js.cut(g, 200)) + "&limit=30"), ex);
            daily = d.join();
            searched = s.join();
        }
        long yearAgo = System.currentTimeMillis() - 365L * 86_400_000;
        Set<String> seen = new HashSet<>();
        List<Paper> out = new ArrayList<>();
        List<Paper> candidates = new ArrayList<>();
        // Goal matches first: they are what the scout should look at most closely.
        for (JsonNode raw : searched) {
            Paper p = toPaper(raw);
            if (p == null) continue;
            Long at = p.published().isEmpty() ? null : parseDate(p.published());
            if (p.published().isEmpty() || (at != null && at >= yearAgo)) candidates.add(p);
        }
        daily.stream().map(Leads::toPaper).filter(Objects::nonNull).forEach(candidates::add);
        for (Paper p : candidates) if (seen.add(p.id())) out.add(p);
        return out;
    }

    public static Llm.Out<Found> researchLeads(Chat chat, String model, String goal, String lang, List<Paper> papers) {
        if (papers.isEmpty()) throw new RuntimeException("Hugging Face returned no papers");
        Map<String, Paper> byId = new HashMap<>();
        for (Paper p : papers) byId.putIfAbsent(p.id(), p);
        StringBuilder list = new StringBuilder();
        for (Paper p : papers.subList(0, Math.min(80, papers.size()))) {
            if (!list.isEmpty()) list.append('\n');
            list.append('[').append(p.id()).append("] ").append(p.title()).append(" (").append(Js.cut(p.published(), 10)).append(", ")
                    .append(p.upvotes()).append(" upvotes): ").append(Js.cut(p.summary(), 280));
        }
        String aim = !Js.trim(goal).isEmpty() ? "the board's goal: \"" + Js.trim(goal) + "\"" : "new products, services or experiences (the board has no goal yet)";
        String user = String.join("\n\n",
                "Pick the 6 papers below that could most inspire new ideas for " + aim + ". Prefer recent, concrete results that make something newly possible. Skip papers that only improve a benchmark score.",
                "For each: the id exactly as given, a plain-language title (no jargon), a summary of what the research makes possible, and a hook: one idea it could inspire for the goal.",
                "Write title, summary and hook in " + LANG_NAME.get(lang) + ".",
                "Papers:\n" + list);
        ChatResult r = chat.chat(new ChatRequest(model,
                "You are a research scout who reads AI papers and explains to designers and founders what each one makes possible.",
                user, "research", RESEARCH_SCHEMA, 0.4, 2500, null));
        List<LeadDraft> leads = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (JsonNode l : Js.arr(r.value().path("leads"))) {
            Paper p = byId.get(Js.str(l.path("id")).replaceAll("^\\[|\\]$", ""));
            if (p == null || !used.add(p.id())) continue; // unknown or repeated id: drop
            String title = clean(l.path("title"), 120);
            Map<String, Object> meta = Js.obj("paperId", p.id(), "paperTitle", p.title(), "published", Js.cut(p.published(), 10), "upvotes", p.upvotes());
            leads.add(new LeadDraft(title.isEmpty() ? p.title() : title, clean(l.path("summary"), 400), clean(l.path("hook"), 300),
                    "https://huggingface.co/papers/" + p.id(), meta));
        }
        return new Llm.Out<>(new Found(leads, 0), r.costUsd());
    }
}
