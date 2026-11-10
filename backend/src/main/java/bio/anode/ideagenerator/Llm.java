package bio.anode.ideagenerator;

import bio.anode.ideagenerator.Providers.Chat;
import bio.anode.ideagenerator.Providers.ChatRequest;
import bio.anode.ideagenerator.Providers.ChatResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every prompt in the app (upstream llm.ts). Which model answers is decided per
 * call (per role, optionally per board).
 *
 * Schemas avoid integer minimum/maximum (Anthropic rejects them), so ranges
 * live in descriptions and are clamped in code.
 */
public final class Llm {

    public record Out<T>(T value, double costUsd) {}

    public record Pitch(String title, String pitch) {
        static Pitch of(Idea i) {
            return new Pitch(i.title(), i.pitch());
        }
    }

    public record Judgement(int originality, int feasibility, int impact, String reason, List<String> keywords) {}

    public record SummaryIdea(String title, String pitch, String kind, Integer sum, String reason) {}

    public record SynthesisIdea(String title, String pitch, Integer sum) {}

    public record SynthesisBoard(String title, String goal, String summary, List<SynthesisIdea> best, List<String> projects) {}

    /** One canonical form so "Public Spaces" and "public space" count as one trend. */
    public static List<String> normalizeKeywords(JsonNode raw) {
        List<String> out = new ArrayList<>();
        for (JsonNode k : Js.arr(raw)) {
            String w = Js.cut(Js.trim(Js.str(k).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} &+-]", " ").replaceAll(Js.WS + "+", " ")), 40);
            if (!w.isEmpty() && !out.contains(w)) out.add(w);
        }
        return out.size() > 4 ? out.subList(0, 4) : out;
    }

    // ---------------------------------------------------------------- schemas

    static final String PITCH_JSON = """
            {"type":"object","properties":{
              "title":{"type":"string","description":"A concrete name for the idea, 2–6 words. No quotes, no trailing period."},
              "pitch":{"type":"string","description":"One or two sentences, at most 40 words: what it is, for whom, and the mechanism that makes it work."}},
             "required":["title","pitch"],"additionalProperties":false}""";
    static final JsonNode PITCH_SCHEMA = Js.parse(PITCH_JSON);
    static final JsonNode EXPAND_SCHEMA = Js.parse("""
            {"type":"object","properties":{"ideas":{"type":"array","description":"Exactly 3 ideas.","items":%s}},
             "required":["ideas"],"additionalProperties":false}""".formatted(PITCH_JSON));
    // The reason comes first so the model argues before it scores.
    static final JsonNode JUDGE_SCHEMA = Js.parse("""
            {"type":"object","properties":{
              "reason":{"type":"string","description":"One sentence, at most 25 words, in the language of the idea: the main strength and the main weakness."},
              "originality":{"type":"integer","description":"Integer 1–5."},
              "feasibility":{"type":"integer","description":"Integer 1–5."},
              "impact":{"type":"integer","description":"Integer 1–5."},
              "keywords":{"type":"array","description":"2–4 keywords for what the idea is about: English, lowercase, singular, 1–2 words each. Reuse a keyword from the vocabulary when one fits.","items":{"type":"string"}}},
             "required":["reason","originality","feasibility","impact","keywords"],"additionalProperties":false}""");

    private static String list(String description) {
        return "{\"type\":\"array\",\"description\":\"" + description + "\",\"items\":{\"type\":\"string\"}}";
    }

    static final JsonNode SUMMARY_SCHEMA = Js.parse("""
            {"type":"object","properties":{
              "headline":{"type":"string","description":"One sentence: where the board stands right now."},
              "themes":{"type":"array","description":"2–5 themes the ideas cluster into.","items":{"type":"object","properties":{
                "name":{"type":"string","description":"Theme name, 1–4 words."},
                "insight":{"type":"string","description":"What the ideas in this theme have in common and what they reveal, 1–2 sentences."},
                "ideas":%s},
                "required":["name","insight","ideas"],"additionalProperties":false}},
              "strongest":{"type":"array","description":"The 1–3 strongest ideas.","items":{"type":"object","properties":{
                "title":{"type":"string","description":"Title exactly as given."},
                "why":{"type":"string","description":"Why it stands out, one sentence."}},
                "required":["title","why"],"additionalProperties":false}},
              "gaps":%s,
              "nextSteps":%s},
             "required":["headline","themes","strongest","gaps","nextSteps"],"additionalProperties":false}""".formatted(
            list("Titles of the ideas in this theme, exactly as given."),
            list("2–4 directions toward the goal that no idea explores yet."),
            list("2–4 concrete next moves on this board, e.g. which ideas to cross or expand.")));

    static final JsonNode SYNTHESIS_SCHEMA = Js.parse("""
            {"type":"object","properties":{
              "headline":{"type":"string","description":"One or two sentences: what the idea work as a whole has produced so far."},
              "insights":%s,
              "standouts":{"type":"array","description":"The 1–5 ideas most worth pursuing, across all boards.","items":{"type":"object","properties":{
                "title":{"type":"string","description":"Title exactly as given."},
                "board":{"type":"string","description":"Board title exactly as given."},
                "why":{"type":"string","description":"Why it stands out, one sentence."}},
                "required":["title","board","why"],"additionalProperties":false}},
              "opportunities":%s,
              "nextSteps":%s},
             "required":["headline","insights","standouts","opportunities","nextSteps"],"additionalProperties":false}""".formatted(
            list("3–6 insights that hold across boards: patterns, recurring weaknesses, what the jury rewards."),
            list("2–4 opportunities: boards whose ideas should cross-breed, or directions nobody has explored."),
            list("2–4 concrete next steps, including which Claude Code projects to start on first.")));

    // ---------------------------------------------------------------- prompts

    static final Map<String, String> LANG_NAME = Map.of("sv", "Swedish", "en", "English");

    static String languageRule(String lang) {
        return "Write in the same language as the source idea(s). If that is unclear or mixed, write in " + LANG_NAME.get(lang) + ". "
                + "If you write Swedish, write idiomatic Swedish: correct agreement, no invented compound words, no anglicisms where a Swedish word exists.";
    }

    static String context(String goal, List<String> existingTitles) {
        List<String> parts = new ArrayList<>();
        if (!Js.trim(goal).isEmpty()) {
            parts.add("The board's goal (the general direction): " + Js.trim(goal)
                    + "\nEvery idea must serve this goal. Keep the source idea's substance, but steer it toward the goal.");
        }
        if (!existingTitles.isEmpty()) {
            List<String> last = existingTitles.subList(Math.max(0, existingTitles.size() - 40), existingTitles.size());
            parts.add("Ideas already on the board (do not repeat or lightly reword them):\n"
                    + last.stream().map(t -> "- " + t).collect(Collectors.joining("\n")));
        }
        return String.join("\n\n", parts);
    }

    static String fmtIdea(String title, String pitch) {
        return pitch != null && !pitch.isEmpty() ? title + ": " + pitch : title;
    }

    static String fmtIdea(Pitch p) {
        return fmtIdea(p.title(), p.pitch());
    }

    private static String join(String... parts) {
        return Stream.of(parts).filter(s -> s != null && !s.isEmpty()).collect(Collectors.joining("\n\n"));
    }

    public static final String JUDGE_SYSTEM = """
            You are a strict, experienced innovation jury. You score ideas on three criteria, each an integer 1–5.

            ORIGINALITY
            1 = a bare topic, a category or an obvious idea everyone has heard ("electric cars", "an app for X", "use AI for Y").
            3 = a known concept applied in a genuinely new context.
            5 = a non-obvious mechanism that few people would think of.

            FEASIBILITY
            1 = needs scientific breakthroughs or is impossible.
            3 = hard, but doable with existing technology within a few years.
            5 = could be piloted within months with means that exist today.

            IMPACT
            1 = trivial, or no clear benefit to anyone.
            3 = meaningful for a specific group.
            5 = large, lasting change for many people.

            Rules:
            - A bare topic or a single word is not an idea yet: originality 1, impact at most 2.
            - Vague ideas that do not say what actually happens score at most 2 on feasibility and impact.
            - Most ideas land around 2–3 per criterion. Reserve 5 for the exceptional; fewer than one idea in ten should reach a total of 13.
            - Judge the idea as written, not the best idea it could become.
            - If the board has a goal, judge against it: impact means impact on that goal, and an idea that does not serve the goal scores at most 2 on impact however good it is otherwise. Say in the reason how well it fits the goal.""";

    private final Chat chat;

    public Llm(Chat chat) {
        this.chat = chat;
    }

    private ChatResult gen(String model, String system, String user, String schemaName, JsonNode schema) {
        return chat.chat(new ChatRequest(model, system, user, schemaName, schema, 0.9));
    }

    static Pitch tidy(JsonNode p) {
        String title = Js.trim(Js.str(p == null ? null : p.path("title")))
                .replaceAll("^[\"'«»“”]+|[\"'«»“”.]+$", "");
        return new Pitch(Js.cut(title, 120), Js.cut(Js.trim(Js.str(p == null ? null : p.path("pitch"))), 600));
    }

    private static List<String> strs(JsonNode x, int n) {
        return Js.arr(x).stream().map(s -> Js.trim(Js.str(s))).filter(s -> !s.isEmpty()).limit(n).toList();
    }

    private static ArrayNode array(List<String> xs) {
        ArrayNode a = Js.M.createArrayNode();
        xs.forEach(a::add);
        return a;
    }

    public Out<List<Pitch>> expand(String model, Board.Flow flow, String goal, Pitch idea, List<String> existingTitles, String lang) {
        String user = join(flow.expand(), "The concept:\n" + fmtIdea(idea), context(goal, existingTitles), languageRule(lang));
        ChatResult r = gen(model, flow.system(), user, "expand", EXPAND_SCHEMA);
        List<Pitch> ideas = Js.arr(r.value().path("ideas")).stream().map(Llm::tidy).filter(i -> !i.title().isEmpty()).toList();
        if (ideas.isEmpty()) throw new RuntimeException("The model returned no ideas");
        return new Out<>(ideas.size() > 3 ? ideas.subList(0, 3) : ideas, r.costUsd());
    }

    public Out<Pitch> combine(String model, Board.Flow flow, String goal, Pitch a, Pitch b, List<String> existingTitles, String lang) {
        String user = join(flow.combine(), "Idea 1:\n" + fmtIdea(a), "Idea 2:\n" + fmtIdea(b), context(goal, existingTitles), languageRule(lang));
        ChatResult r = gen(model, flow.system(), user, "combine", PITCH_SCHEMA);
        Pitch idea = tidy(r.value());
        if (idea.title().isEmpty()) throw new RuntimeException("The model returned no idea");
        return new Out<>(idea, r.costUsd());
    }

    /** Turn a long text or a fetched web page into one seed idea. */
    public Out<Pitch> distill(String model, Board.Flow flow, String goal, String text, String url, String lang) {
        String user = join(
                "Distill the source below into ONE seed idea for a brainstorm: the most promising concept, problem or opportunity in it. Do not summarise the whole text.",
                url != null ? "Source: " + url : "",
                "---\n" + text + "\n---",
                context(goal, List.of()),
                languageRule(lang));
        ChatResult r = gen(model, flow.system(), user, "seed", PITCH_SCHEMA);
        Pitch idea = tidy(r.value());
        if (idea.title().isEmpty()) throw new RuntimeException("The model returned no idea");
        return new Out<>(idea, r.costUsd());
    }

    private static String writeIn(String lang) {
        return "Write in " + LANG_NAME.get(lang) + ("sv".equals(lang) ? " (idiomatic Swedish)" : "");
    }

    /** A synthesis of everything on a board. ideas: sorted strongest first. */
    public Out<ObjectNode> summarize(String model, String goal, String lang, List<SummaryIdea> ideas) {
        String lines = ideas.stream().limit(120).map(i -> "- " + fmtIdea(i.title(), i.pitch())
                        + (i.sum() != null ? " [jury " + i.sum() + "/15" + (i.reason() != null && !i.reason().isEmpty() ? ": " + i.reason() : "") + "]" : " [not judged]")
                        + ("guest".equals(i.kind()) ? " (guest from another board)" : ""))
                .collect(Collectors.joining("\n"));
        String user = String.join("\n\n",
                !Js.trim(goal).isEmpty() ? "The board's goal: " + Js.trim(goal) : "The board has no stated goal.",
                "The " + ideas.size() + " ideas on the board, strongest first:\n" + lines,
                "Summarise the board for the people working on it: the themes, the strongest ideas, the gaps toward the goal, and what to do next. Be specific and honest; do not praise.",
                writeIn(lang) + ". Keep idea titles exactly as given.");
        ChatResult r = chat.chat(new ChatRequest(model,
                "You are a facilitator who has watched a whole brainstorm and now sums it up crisply for the team.",
                user, "summary", SUMMARY_SCHEMA, 0.4, 2500, null));
        JsonNode v = r.value();
        ObjectNode s = Js.object();
        s.put("headline", Js.trim(Js.str(v.path("headline"))));
        ArrayNode themes = s.putArray("themes");
        Js.arr(v.path("themes")).stream().limit(6).forEach(t -> {
            ObjectNode o = themes.addObject();
            o.put("name", Js.trim(Js.str(t.path("name"))));
            o.put("insight", Js.trim(Js.str(t.path("insight"))));
            o.set("ideas", array(strs(t.path("ideas"), 30)));
        });
        ArrayNode strongest = s.putArray("strongest");
        Js.arr(v.path("strongest")).stream().limit(3).forEach(t ->
                strongest.addObject().put("title", Js.trim(Js.str(t.path("title")))).put("why", Js.trim(Js.str(t.path("why")))));
        s.set("gaps", array(strs(v.path("gaps"), 5)));
        s.set("nextSteps", array(strs(v.path("nextSteps"), 5)));
        return new Out<>(s, r.costUsd());
    }

    /** The report's conclusions: one synthesis across every board. */
    public Out<ObjectNode> synthesize(String model, String lang, int days, List<SynthesisBoard> boards) {
        String blocks = boards.stream().map(b -> Stream.of(
                        "## " + b.title(),
                        !b.goal().isEmpty() ? "Goal: " + b.goal() : "No goal.",
                        !b.summary().isEmpty() ? "Board summary: " + b.summary() : "",
                        !b.best().isEmpty()
                                ? "Best ideas:\n" + b.best().stream().map(i -> "- " + fmtIdea(i.title(), i.pitch()) + (i.sum() != null ? " [jury " + i.sum() + "/15]" : ""))
                                .collect(Collectors.joining("\n"))
                                : "No judged ideas yet.",
                        !b.projects().isEmpty() ? "Claude Code projects: " + String.join("; ", b.projects()) : "")
                .filter(x -> !x.isEmpty()).collect(Collectors.joining("\n")))
                .collect(Collectors.joining("\n\n"));
        String user = String.join("\n\n",
                "These are the idea boards and what they produced (the report covers the last " + days + " days):",
                blocks,
                "Write the conclusions of the report for the people running the boards: what holds across boards, which ideas are most worth pursuing, where boards should cross-breed, and what to do next. Be specific and honest; do not praise.",
                writeIn(lang) + ". Keep idea and board titles exactly as given.");
        ChatResult r = chat.chat(new ChatRequest(model,
                "You are an innovation lead who reads the results of several idea boards and writes the conclusions of a short report.",
                user, "synthesis", SYNTHESIS_SCHEMA, 0.4, 2500, null));
        JsonNode v = r.value();
        ObjectNode s = Js.object();
        s.put("headline", Js.trim(Js.str(v.path("headline"))));
        s.set("insights", array(strs(v.path("insights"), 8)));
        ArrayNode standouts = s.putArray("standouts");
        Js.arr(v.path("standouts")).stream().limit(5).forEach(t -> standouts.addObject()
                .put("title", Js.trim(Js.str(t.path("title")))).put("board", Js.trim(Js.str(t.path("board")))).put("why", Js.trim(Js.str(t.path("why")))));
        s.set("opportunities", array(strs(v.path("opportunities"), 6)));
        s.set("nextSteps", array(strs(v.path("nextSteps"), 6)));
        return new Out<>(s, r.costUsd());
    }

    /** guidance: the board's own jury instructions, on top of the fixed scale. */
    public Out<Judgement> judge(String model, String goal, Pitch idea, String lang, String guidance, List<String> vocabulary) {
        String g = Js.trim(guidance == null ? "" : guidance);
        String system = g.isEmpty() ? JUDGE_SYSTEM : JUDGE_SYSTEM + "\n\nAdditional guidance from this board's facilitator:\n" + g;
        String user = join(
                !Js.trim(goal).isEmpty() ? "The board's goal (the general direction): " + Js.trim(goal) : "",
                "The idea:\n" + fmtIdea(idea),
                "Write the reason in the language of the idea; if unclear, in " + LANG_NAME.get(lang) + ".",
                vocabulary != null && !vocabulary.isEmpty() ? "Keyword vocabulary in use (reuse when one fits): " + String.join(", ", vocabulary) : "");
        ChatResult r = chat.chat(new ChatRequest(model, system, user, "judgement", JUDGE_SCHEMA, 0.2, 400, null));
        JsonNode v = r.value();
        return new Out<>(new Judgement(
                Score.clampScore(v.path("originality")),
                Score.clampScore(v.path("feasibility")),
                Score.clampScore(v.path("impact")),
                Js.cut(Js.trim(Js.str(v.path("reason"))), 400),
                normalizeKeywords(v.path("keywords"))), r.costUsd());
    }
}
