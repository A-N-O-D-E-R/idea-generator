package bio.anode.ideagenerator;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A board (upstream db.ts Board). Every board is self-contained: its goal, its
 * own creation flow, jury guidance, researchers, autopilot pace. Templates
 * only seed a new board's flow.
 */
public record Board(
        long id,
        String title,
        String goal,
        Config config,
        String createdAt,
        String updatedAt,
        Long originIdeaId,
        String originIdeaTitle,
        Long originBoardId,
        String originBoardTitle,
        /* BoardSummary as stored: headline, themes, strongest, gaps, nextSteps, ideaCount, lang, createdAt */
        ObjectNode summary,
        /* owner; never sent to the browser */
        @JsonIgnore String tenantId) {

    public record Flow(String system, String expand, String combine) {}

    public record Researcher(boolean enabled, String focus) {}

    public record Researchers(Researcher x, Researcher research) {}

    public record Rate(int stepsPerHour, double usdPerDay) {}

    public record Config(
            String flowName,
            Flow flow,
            String judgeGuidance,
            Researchers researchers,
            /* fallback language for generated text: sv | en */
            String lang,
            /* manual | auto */
            String mode,
            int autopilotSeconds,
            Rate rate,
            boolean autoProjectOnGold,
            /* fitness sharing on judge keywords, so evolution keeps several families alive (not in upstream) */
            boolean diversity,
            /* per-role "provider:model" overrides; empty = the app-wide setting */
            Map<String, String> models) {}

    public record Template(long id, String name, String system, String expand, String combine, int sort) {}

    // Templates for a board's creation flow. The prompts are in English; the
    // output language is decided per request (same language as the seed).
    public static final List<Template> DEFAULT_TEMPLATES = List.of(
            new Template(0, "Uppfinnaren · Inventor",
                    "You are a brilliant, practical inventor. You think in mechanisms: what exactly happens, for whom, and why it works. You avoid buzzwords.",
                    "Generate 3 new ideas that grow out of the concept. Make them clearly different from each other: one close variation that sharpens it, one that borrows a mechanism from a completely different field, and one bold leap.",
                    "Fuse the two ideas into ONE new concept that neither could be alone. Name the specific mechanism that connects them; do not just list both.", 0),
            new Template(0, "Affärsstrategen · Business",
                    "You are a sharp business strategist who has built and sold companies. You care about who pays, why now, and what makes it hard to copy.",
                    "Generate 3 ideas for products, services or business models that grow out of the concept. Each must say who the customer is and how money is made, and they must differ from each other.",
                    "Merge the two ideas into ONE business concept where each part strengthens the other's business model. State who pays and why.", 1),
            new Template(0, "Omställaren · Sustainability",
                    "You are a systems thinker working on sustainability and local communities. You favour solutions that cut resource use and that ordinary people and municipalities can start with today.",
                    "Generate 3 ideas that grow out of the concept and make it more sustainable socially, ecologically or economically. Make them differ in scale: household, organisation, and region.",
                    "Combine the two ideas into ONE solution where the sustainability gain of one multiplies the other. Be concrete about what changes.", 2),
            new Template(0, "Galningen · Wild card",
                    "You are a playful, fearless provocateur. You turn assumptions upside down and find the idea nobody dares say out loud, but every idea must still be explainable in two sentences and possible to actually try.",
                    "Generate 3 surprising, unconventional ideas that grow out of the concept. Each must break at least one obvious assumption.",
                    "Crash the two ideas together into ONE unexpected concept. The stranger the connection the better, as long as it can actually be done.", 3));

    public static Config defaultConfig() {
        return defaultConfig(DEFAULT_TEMPLATES.getFirst());
    }

    public static Config defaultConfig(Template t) {
        return new Config(t.name(), new Flow(t.system(), t.expand(), t.combine()), "",
                new Researchers(new Researcher(true, ""), new Researcher(true, "")),
                "sv", "manual", 10, new Rate(60, 1), true, true, new LinkedHashMap<>());
    }

    private static String s(JsonNode v, int max, String fallback) {
        return v != null && v.isString() ? Js.cut(Js.trim(v.stringValue()), max) : fallback;
    }

    private static String nonEmpty(JsonNode v, int max, String fallback) {
        String out = s(v, max, fallback);
        return out.isEmpty() ? fallback : out;
    }

    private static boolean b(JsonNode v, boolean fallback) {
        return v != null && v.isBoolean() ? v.booleanValue() : fallback;
    }

    /** Merge an untrusted partial config onto a valid one, keeping only well-typed values. */
    public static Config mergeConfig(Config base, JsonNode raw) {
        JsonNode r = raw != null && raw.isContainer() ? raw : Js.object();
        JsonNode rx = r.path("researchers").path("x");
        JsonNode rr = r.path("researchers").path("research");
        double secs = Js.num(r.path("autopilotSeconds"));
        double sph = Js.num(r.path("rate").path("stepsPerHour"));
        double usd = Js.num(r.path("rate").path("usdPerDay"));
        Map<String, String> models = new LinkedHashMap<>(base.models());
        for (String role : Providers.ROLES) {
            JsonNode v = r.path("models").path(role);
            if (v.isString()) {
                String t = Js.cut(Js.trim(v.stringValue()), 200);
                if (!t.isEmpty()) models.put(role, t);
                else models.remove(role);
            }
        }
        JsonNode lang = r.path("lang"), mode = r.path("mode");
        return new Config(
                nonEmpty(r.path("flowName"), 80, base.flowName()),
                new Flow(
                        nonEmpty(r.path("flow").path("system"), 4000, base.flow().system()),
                        nonEmpty(r.path("flow").path("expand"), 4000, base.flow().expand()),
                        nonEmpty(r.path("flow").path("combine"), 4000, base.flow().combine())),
                s(r.path("judgeGuidance"), 4000, base.judgeGuidance()),
                new Researchers(
                        new Researcher(b(rx.path("enabled"), base.researchers().x().enabled()),
                                s(rx.path("focus"), 300, base.researchers().x().focus())),
                        new Researcher(b(rr.path("enabled"), base.researchers().research().enabled()),
                                s(rr.path("focus"), 300, base.researchers().research().focus()))),
                lang.isString() && List.of("en", "sv").contains(lang.stringValue()) ? lang.stringValue() : base.lang(),
                mode.isString() && List.of("auto", "manual").contains(mode.stringValue()) ? mode.stringValue() : base.mode(),
                Double.isFinite(secs) ? (int) Math.min(3600, Math.max(3, Math.round(secs))) : base.autopilotSeconds(),
                new Rate(
                        Double.isFinite(sph) ? (int) Math.min(3600, Math.max(1, Math.round(sph))) : base.rate().stepsPerHour(),
                        Double.isFinite(usd) ? Math.min(1000, Math.max(0, Math.round(usd * 100) / 100.0)) : base.rate().usdPerDay()),
                b(r.path("autoProjectOnGold"), base.autoProjectOnGold()),
                b(r.path("diversity"), base.diversity()),
                models);
    }
}
