package bio.anode.ideagenerator;

import bio.anode.ideagenerator.Providers.Chat;
import bio.anode.ideagenerator.Providers.ChatRequest;
import bio.anode.ideagenerator.Providers.ChatResult;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import tools.jackson.databind.JsonNode;

/**
 * Turn an idea into a folder Claude Code can start from (upstream project.ts):
 * a CLAUDE.md brief, a README with the start command, an idea.json, an Obsidian
 * vault for notes (gitignored) and an empty git repo. Never overwrites: a taken
 * name gets -2, -3 and so on.
 */
public final class Project {
    private Project() {}

    public record Brief(String name, String slug, String oneLiner, String problem, String audience, List<String> mvp,
                        List<String> outOfScope, String stack, List<String> firstSteps, List<String> openQuestions, List<String> risks) {}

    private static String list(String description) {
        return "{\"type\":\"array\",\"description\":\"" + description + "\",\"items\":{\"type\":\"string\"}}";
    }

    static final JsonNode BRIEF_SCHEMA = Js.parse("""
            {"type":"object","properties":{
              "name":{"type":"string","description":"Project name, 1–4 words."},
              "slug":{"type":"string","description":"Folder name: lowercase ASCII kebab-case, at most 40 characters."},
              "oneLiner":{"type":"string","description":"One sentence: what it is and for whom."},
              "problem":{"type":"string","description":"The problem or opportunity, 2–3 sentences."},
              "audience":{"type":"string","description":"Who it is for, concretely."},
              "mvp":%s,
              "outOfScope":%s,
              "stack":{"type":"string","description":"A simple suggested stack and why, 1–3 sentences."},
              "firstSteps":%s,
              "openQuestions":%s,
              "risks":%s},
             "required":["name","slug","oneLiner","problem","audience","mvp","outOfScope","stack","firstSteps","openQuestions","risks"],
             "additionalProperties":false}""".formatted(
            list("3–6 features of the smallest version that tests the core mechanism."),
            list("2–5 things deliberately left out of the first version."),
            list("3–6 concrete first steps for a coding agent, in order."),
            list("2–5 questions a human must answer."),
            list("2–4 main risks.")));

    static final Map<String, String> LANG_NAME = Map.of("sv", "Swedish", "en", "English");

    private static String fmt(Idea i) {
        return Llm.fmtIdea(i.title(), i.pitch());
    }

    public static String slugify(String s) {
        String out = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("[\\u0300-\\u036f]", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        out = Js.cut(out, 48).replaceAll("-+$", "");
        return out.isEmpty() ? "idea" : out;
    }

    public static String expandHome(String p) {
        String t = Js.trim(p);
        return t.equals("~") || t.startsWith("~/") ? Path.of(System.getProperty("user.home"), t.substring(1)).toString() : t;
    }

    private static boolean truthy(JsonNode n) {
        return !Js.absent(n) && !Js.str(n).isEmpty() && !(n.isBoolean() && !n.booleanValue()) && !(n.isNumber() && n.asDouble() == 0);
    }

    public static Llm.Out<Brief> writeBrief(Chat chat, String model, Idea idea, String goal, List<Idea> lineage, String lang) {
        Integer sum = Score.scoreSum(idea);
        String user = java.util.stream.Stream.of(
                        "Turn this idea into a kickoff brief for a coding agent (Claude Code) that will build it from an empty folder.",
                        "The idea:\n" + fmt(idea),
                        !Js.trim(goal).isEmpty() ? "The goal of the board it came from: " + Js.trim(goal) : "",
                        sum != null ? "Jury verdict: originality " + idea.originality() + ", feasibility " + idea.feasibility() + ", impact "
                                + idea.impact() + " (of 5). " + (idea.reason() == null ? "" : idea.reason()) : "",
                        !lineage.isEmpty() ? "It grew out of these ideas:\n" + lineage.stream().map(i -> "- " + fmt(i)).collect(Collectors.joining("\n")) : "",
                        "Write in the language of the idea; if that is unclear, in " + LANG_NAME.get(lang) + ". If Swedish, write idiomatic Swedish. The slug is always ASCII.")
                .filter(x -> !x.isEmpty()).collect(Collectors.joining("\n\n"));
        ChatResult r = chat.chat(new ChatRequest(model,
                "You are a senior product engineer who turns raw ideas into crisp kickoff briefs. You favour the smallest build that tests the core mechanism, and you name the hard parts honestly.",
                user, "brief", BRIEF_SCHEMA, 0.5, 2500, null));
        JsonNode v = Js.absent(r.value()) ? Js.object() : r.value();
        String name = Js.trim(Js.absent(v.path("name")) ? idea.title() : Js.str(v.path("name")));
        String slugSource = truthy(v.path("slug")) ? Js.str(v.path("slug")) : truthy(v.path("name")) ? Js.str(v.path("name")) : idea.title();
        Brief brief = new Brief(
                name.isEmpty() ? idea.title() : name,
                slugify(slugSource),
                Js.trim(Js.str(v.path("oneLiner"))),
                Js.trim(Js.str(v.path("problem"))),
                Js.trim(Js.str(v.path("audience"))),
                arr(v.path("mvp")), arr(v.path("outOfScope")),
                Js.trim(Js.str(v.path("stack"))),
                arr(v.path("firstSteps")), arr(v.path("openQuestions")), arr(v.path("risks")));
        return new Llm.Out<>(brief, r.costUsd());
    }

    private static List<String> arr(JsonNode x) {
        return Js.arr(x).stream().map(s -> Js.trim(Js.str(s))).filter(s -> !s.isEmpty()).limit(8).toList();
    }

    record Labels(String born, String brief, String idea, String goal, String verdict, String o, String f, String i, String handset,
                  String problem, String audience, String mvp, String out, String stack, String steps, String questions, String risks,
                  String origin, String source, Map<String, String> kinds, String fromBoard, String agreements, List<String> rules,
                  String start, String then, String vaultIntro) {}

    static final Map<String, Labels> L = Map.of(
            "sv", new Labels("Projektet föddes på en idétavla i Idégeneratorn",
                    "Den här filen är briefen: läs den innan du börjar och håll den aktuell när beslut fattas.",
                    "Idén", "Tavlans mål", "Juryns bedömning", "Originalitet", "Genomförbarhet", "Effekt", "Medalj satt för hand",
                    "Problem", "Vem det är för", "MVP: minsta bygget som prövar kärnan", "Utanför första versionen", "Föreslagen stack",
                    "Första stegen", "Öppna frågor", "Risker", "Var idén kom ifrån", "källa",
                    Map.of("seed", "Frö", "expand", "Förgrening", "combine", "Korsning", "guest", "Gäst"), "från tavlan",
                    "Arbetsregler",
                    List.of("Bygg först den minsta versionen som prövar kärnmekanismen; lägg till komplexitet först när den fungerar.",
                            "Verifiera varje steg genom att köra det, inte bara genom att läsa koden.",
                            "Fråga innan något kostar pengar (betalda API:er) eller publiceras.",
                            "Anteckningar och idéer hör hemma i `vault/` (Obsidian), som aldrig checkas in."),
                    "Kom igång", "Säg sedan: \"Läs CLAUDE.md och föreslå en plan för första steget.\"",
                    "Arbetsanteckningar för projektet. Mappen är gitignorerad."),
            "en", new Labels("This project was born on an idea board in Idea Generator",
                    "This file is the brief: read it before you start, and keep it up to date as decisions are made.",
                    "The idea", "Board goal", "Jury verdict", "Originality", "Feasibility", "Impact", "Medal set by hand",
                    "Problem", "Who it is for", "MVP: the smallest build that tests the core", "Out of scope for now", "Suggested stack",
                    "First steps", "Open questions", "Risks", "Where the idea came from", "source",
                    Map.of("seed", "Seed", "expand", "Branch", "combine", "Cross", "guest", "Guest"), "from board",
                    "Working agreements",
                    List.of("Build the smallest version that tests the core mechanism first; add complexity only once that works.",
                            "Verify each step by running it, not just by reading the code.",
                            "Ask before anything that costs money (paid APIs) or publishes anything.",
                            "Notes and ideas belong in `vault/` (Obsidian), which is never committed."),
                    "Getting started", "Then say: \"Read CLAUDE.md and propose a plan for the first step.\"",
                    "Working notes for the project. This folder is gitignored."));

    static final String[] MEDAL = {"", " 🥉", " 🥈", " 🥇"};

    private static String bullets(List<String> xs) {
        return xs.stream().map(x -> "- " + x).collect(Collectors.joining("\n"));
    }

    public static String renderClaudeMd(Brief b, Idea idea, String goal, List<Idea> lineage, String lang, String date) {
        Labels t = L.get(lang);
        Integer sum = Score.scoreSum(idea);
        Integer m = Score.medal(idea);
        List<String> out = new ArrayList<>(List.of("# " + b.name(), "", "> " + b.oneLiner(), "", t.born() + " (" + date + "). " + t.brief(), "",
                "## " + t.idea(), "", Js.trim("**" + idea.title() + ".** " + idea.pitch()), ""));
        if (!Js.trim(goal).isEmpty()) out.addAll(List.of("## " + t.goal(), "", Js.trim(goal), ""));
        if (sum != null || idea.medalOverride() != null) {
            out.addAll(List.of("## " + t.verdict(), ""));
            if (sum != null) {
                out.addAll(List.of(t.o() + " " + idea.originality() + " · " + t.f() + " " + idea.feasibility() + " · " + t.i() + " " + idea.impact()
                        + " = " + sum + "/15" + (m != null && m != 0 ? MEDAL[m] : ""), "", idea.reason() == null ? "" : idea.reason(), ""));
            }
            if (idea.medalOverride() != null) {
                String icon = MEDAL[idea.medalOverride()];
                out.addAll(List.of("*" + t.handset() + ":" + (icon.isEmpty() ? " –" : icon) + "*", ""));
            }
        }
        out.addAll(List.of(
                "## " + t.problem(), "", b.problem(), "",
                "## " + t.audience(), "", b.audience(), "",
                "## " + t.mvp(), "", bullets(b.mvp()), "",
                "## " + t.out(), "", bullets(b.outOfScope()), "",
                "## " + t.stack(), "", b.stack(), "",
                "## " + t.steps(), "", IntStream.range(0, b.firstSteps().size()).mapToObj(n -> (n + 1) + ". " + b.firstSteps().get(n))
                        .collect(Collectors.joining("\n")), "",
                "## " + t.questions(), "", bullets(b.openQuestions()), "",
                "## " + t.risks(), "", bullets(b.risks()), ""));
        if (!lineage.isEmpty()) {
            out.addAll(List.of("## " + t.origin(), ""));
            for (Idea i : lineage) {
                String where = "guest".equals(i.kind()) && i.originBoardTitle() != null && !i.originBoardTitle().isEmpty()
                        ? " (" + t.fromBoard() + " " + i.originBoardTitle() + ")" : "";
                out.add("- " + t.kinds().getOrDefault(i.kind(), i.kind()) + where + ": **" + i.title() + "**"
                        + (i.pitch() != null && !i.pitch().isEmpty() ? " " + i.pitch() : "")
                        + (i.sourceUrl() != null && !i.sourceUrl().isEmpty() ? " (" + t.source() + ": " + i.sourceUrl() + ")" : ""));
            }
            out.add("");
        }
        out.addAll(List.of("## " + t.agreements(), "", bullets(t.rules()), ""));
        return String.join("\n", out);
    }

    /** Create a fresh, uniquely named folder under root. */
    private static Path uniqueDir(Path root, String slug) throws IOException {
        Files.createDirectories(root);
        for (int n = 1; n < 100; n++) {
            Path dir = root.resolve(n == 1 ? slug : slug + "-" + n);
            try {
                return Files.createDirectory(dir); // not recursive: fails if it exists, so we never write into someone else's folder
            } catch (FileAlreadyExistsException taken) {
                // try the next suffix
            }
        }
        throw new IOException("No free folder name for " + slug);
    }

    /** Returns the absolute path of the new folder. */
    public static String writeProject(String root, Brief brief, Idea idea, String goal, List<Idea> lineage, String lang, boolean gitInit) throws IOException {
        String expanded = expandHome(root);
        if (!Path.of(expanded).isAbsolute()) {
            throw new IOException("The projects folder must be an absolute path or start with ~ (got \"" + root + "\")");
        }
        Path dir = uniqueDir(Path.of(expanded).normalize(), slugify(brief.slug()));
        Labels t = L.get(lang);
        String date = Js.utcToday();
        Files.writeString(dir.resolve("CLAUDE.md"), renderClaudeMd(brief, idea, goal, lineage, lang, date));
        Files.writeString(dir.resolve("README.md"), String.join("\n",
                "# " + brief.name(), "", brief.oneLiner(), "", "## " + t.start(), "", "```bash", "cd \"" + dir + "\"", "claude", "```", "", t.then(), ""));
        Files.writeString(dir.resolve("idea.json"), Js.M.writerWithDefaultPrettyPrinter().writeValueAsString(
                Js.obj("createdAt", Js.isoNow(), "goal", goal, "idea", idea, "lineage", lineage, "brief", brief)) + "\n");
        Files.writeString(dir.resolve(".gitignore"), String.join("\n",
                "node_modules/", ".env", ".DS_Store", "# Obsidian-vault: arbetsanteckningar, aldrig i repot", "vault/", ".obsidian/", ""));
        Files.createDirectory(dir.resolve("vault"));
        List<String> home = new ArrayList<>(List.of("# " + brief.name(), "", t.vaultIntro(), "", "## " + t.idea(), "", fmt(idea), "", "## " + t.questions(), ""));
        brief.openQuestions().forEach(q -> home.add("- [ ] " + q));
        home.add("");
        Files.writeString(dir.resolve("vault").resolve("Home.md"), String.join("\n", home));
        if (gitInit) {
            try {
                new ProcessBuilder("git", "init", "-q", "-b", "main").directory(dir.toFile()).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
            } catch (IOException | InterruptedException ignored) {
                // no git: the folder is still usable
            }
        }
        return dir.toString();
    }

    /** Zip a project folder as <folder>/...; regular files only, symlinks are skipped (they could point anywhere). */
    public static void zip(Path dir, java.io.OutputStream out) throws IOException {
        Path base = dir.getParent() == null ? dir : dir.getParent();
        try (var zip = new java.util.zip.ZipOutputStream(out); var files = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                zip.putNextEntry(new java.util.zip.ZipEntry(base.relativize(f).toString().replace('\\', '/')));
                Files.copy(f, zip);
                zip.closeEntry();
            }
        }
    }

    /** Open a folder in the OS file manager (upstream used macOS `open`). */
    public static void reveal(String path) {
        String cmd = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac") ? "open" : "xdg-open";
        try {
            new ProcessBuilder(cmd, path).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException ignored) {
            // nothing to open with
        }
    }
}
