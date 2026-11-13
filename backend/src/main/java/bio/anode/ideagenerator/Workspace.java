package bio.anode.ideagenerator;

import bio.anode.ideagenerator.model.Board;
import bio.anode.ideagenerator.model.Idea;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import bio.anode.ideagenerator.Providers.Chat;
import bio.anode.ideagenerator.Providers.ChatRequest;
import bio.anode.ideagenerator.Providers.ChatResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * Files a project into an Idea workspace (the layout of its AGENT.md): an
 * {@code ideas/<slug>/} folder with PITCH.md and LINK.md, plus a row at the top
 * of the INDEX.md table. Status is {@code raw}: research is left to the
 * workspace's own new-idea workflow. Never overwrites: a slug already in the
 * folder or the index counts as filed.
 */
public final class Workspace {
    private Workspace() {}

    /** existed: the slug or a duplicate was already there (path points at it); related: slugs linked both ways. */
    public record Filed(String path, boolean existed, List<String> related) {}

    /** One row of the INDEX.md table. */
    public record Row(String slug, String title, String tags, String summary) {}

    /** A code repo found under the repos roots: its folder and the start of its README. */
    public record Repo(String path, String summary) {}

    public record RepoHit(String path, String why) {}

    public record PaperHit(Leads.Paper paper, String why) {}

    /**
     * The judge of "have we had this before": a slug it duplicates, slugs it relates to (each with a reason),
     * repos with reusable work, and papers that are prior work.
     */
    public record Match(String duplicateOf, Map<String, String> related, List<RepoHit> repos, List<PaperHit> papers) {
        public static final Match NONE = new Match(null, Map.of(), List.of(), List.of());

        public Match(String duplicateOf, Map<String, String> related) {
            this(duplicateOf, related, List.of(), List.of());
        }
    }

    static final Pattern ROW = Pattern.compile("^\\|[^|]*\\|\\s*\\[([a-z0-9-]+)]\\(ideas/[^)]*\\)\\s*\\|([^|]*)\\|([^|]*)\\|[^|]*\\|(.*)\\|\\s*$", Pattern.MULTILINE);

    public static List<Row> rows(String indexText) {
        List<Row> out = new ArrayList<>();
        Matcher m = ROW.matcher(indexText);
        while (m.find()) out.add(new Row(m.group(1), m.group(2).trim(), m.group(3).trim(), m.group(4).trim()));
        return out;
    }

    public static String readIndex(String root) throws IOException {
        Path index = Path.of(Project.expandHome(root)).normalize().resolve("INDEX.md");
        if (!Files.isRegularFile(index)) throw new IOException("Not an idea workspace (no INDEX.md): " + index.getParent());
        return Files.readString(index);
    }

    static final int MAX_REPOS = 150;

    /**
     * Sub-folders of each root (comma or newline separated, absolute or ~), with the first prose of their README.
     * ponytail: one level deep and README-only, add a deeper scan if repos sit in group folders (e.g. org/team/*).
     */
    public static List<Repo> scanRepos(String roots) {
        List<Repo> out = new ArrayList<>();
        for (String root : roots.split("[,\\n]")) {
            String r = Project.expandHome(root);
            if (r.isEmpty() || !Path.of(r).isAbsolute() || !Files.isDirectory(Path.of(r))) continue;
            try (var dirs = Files.list(Path.of(r).normalize())) {
                for (Path d : dirs.filter(Files::isDirectory).filter(d -> !d.getFileName().toString().startsWith(".")).sorted().toList()) {
                    if (out.size() >= MAX_REPOS) return out;
                    out.add(new Repo(d.toString(), readmeSummary(d)));
                }
            } catch (IOException ignored) {
                // unreadable root: skip it
            }
        }
        return out;
    }

    /** First lines of prose of a README (no headings markup, badges, images or HTML), at most 300 characters. */
    static String readmeSummary(Path dir) {
        for (String name : List.of("README.md", "README", "readme.md", "README.txt")) {
            Path f = dir.resolve(name);
            if (!Files.isRegularFile(f)) continue;
            try {
                List<String> prose = new ArrayList<>();
                boolean fenced = false;
                for (String raw : Files.readAllLines(f).stream().limit(80).toList()) {
                    String l = raw.trim();
                    if (l.startsWith("```")) fenced = !fenced;
                    else if (!fenced && !l.isEmpty() && !l.startsWith("[![") && !l.startsWith("![") && !l.startsWith("<")) prose.add(l.replaceFirst("^#+\\s*", ""));
                }
                return Js.cut(String.join(" ", prose), 300);
            } catch (IOException | java.io.UncheckedIOException e) {
                return "";
            }
        }
        return "";
    }

    static final JsonNode MATCH_SCHEMA = Js.parse("""
            {"type":"object","properties":{
              "duplicateOf":{"type":"string","description":"Slug of an existing idea that is the same idea (same problem AND same mechanism), or empty string if none."},
              "related":{"type":"array","description":"0–4 existing ideas that are close but different.","items":{"type":"object","properties":{
                "slug":{"type":"string"},
                "why":{"type":"string","description":"One short line: how they relate (shares tech / same market / could merge / alternative to...)."}},
                "required":["slug","why"],"additionalProperties":false}},
              "repos":{"type":"array","description":"0–3 code repos (by exact path) with work that could be reused for the new idea.","items":{"type":"object","properties":{
                "path":{"type":"string"},
                "why":{"type":"string","description":"One short line: what is reusable."}},
                "required":["path","why"],"additionalProperties":false}},
              "papers":{"type":"array","description":"0–4 papers (by exact id) that are real prior work for the new idea; none if they only share words.","items":{"type":"object","properties":{
                "id":{"type":"string"},
                "why":{"type":"string","description":"One short line: what it already does, and what the new idea adds."}},
                "required":["id","why"],"additionalProperties":false}}},
             "required":["duplicateOf","related","repos","papers"],"additionalProperties":false}""");

    public static Llm.Out<Match> match(Chat chat, String model, Project.Brief b, Idea idea, List<Row> rows) {
        return match(chat, model, b, idea, rows, List.of(), List.of());
    }

    /** One LLM call against the index, the repos and the papers. Only slugs, paths and ids it was shown survive. */
    public static Llm.Out<Match> match(Chat chat, String model, Project.Brief b, Idea idea, List<Row> rows, List<Repo> repos, List<Leads.Paper> papers) {
        if (rows.isEmpty() && repos.isEmpty() && papers.isEmpty()) return new Llm.Out<>(Match.NONE, 0);
        List<String> parts = new ArrayList<>(List.of("New idea:\n" + b.name() + ": " + b.oneLiner() + "\n" + Llm.fmtIdea(idea.title(), idea.pitch())));
        if (!rows.isEmpty()) parts.add("Existing ideas:\n" + rows.stream().map(r -> "- " + r.slug() + ": " + r.title() + " [" + r.tags() + "] " + r.summary())
                .collect(Collectors.joining("\n")));
        if (!repos.isEmpty()) parts.add("Existing code repos:\n" + repos.stream().map(r -> "- " + r.path() + ": " + (r.summary().isEmpty() ? "(no README)" : r.summary()))
                .collect(Collectors.joining("\n")));
        if (!papers.isEmpty()) parts.add("Papers found by a search:\n" + papers.stream().map(p -> "- " + p.id() + ": " + p.title() + ". " + Js.cut(p.summary(), 300))
                .collect(Collectors.joining("\n")));
        parts.add("Is the new idea a duplicate of an existing idea? Which ideas are related, which repos hold reusable work, and which papers are prior work?");
        ChatResult r = chat.chat(new ChatRequest(model,
                "You keep an index of ideas tidy. You are strict about duplicates: only the same problem solved by the same mechanism is a duplicate. A shared topic is at most related. Name a repo or a paper only if it truly applies.",
                String.join("\n\n", parts), "workspace_match", MATCH_SCHEMA, 0.2, 1200, null));
        JsonNode v = Js.absent(r.value()) ? Js.object() : r.value();
        java.util.Set<String> known = rows.stream().map(Row::slug).collect(Collectors.toSet());
        String dup = Js.trim(Js.str(v.path("duplicateOf")));
        Map<String, String> related = new LinkedHashMap<>();
        for (JsonNode x : Js.arr(v.path("related"))) {
            String slug = Js.trim(Js.str(x.path("slug")));
            if (known.contains(slug) && !slug.equals(dup) && related.size() < 4) related.put(slug, cell(Js.str(x.path("why"))));
        }
        java.util.Set<String> paths = repos.stream().map(Repo::path).collect(Collectors.toSet());
        List<RepoHit> repoHits = Js.arr(v.path("repos")).stream()
                .map(x -> new RepoHit(Js.trim(Js.str(x.path("path"))), cell(Js.str(x.path("why")))))
                .filter(h -> paths.contains(h.path())).distinct().limit(3).toList();
        Map<String, Leads.Paper> byId = new LinkedHashMap<>();
        papers.forEach(p -> byId.putIfAbsent(p.id(), p));
        List<PaperHit> paperHits = Js.arr(v.path("papers")).stream()
                .map(x -> Map.entry(Js.trim(Js.str(x.path("id"))), cell(Js.str(x.path("why")))))
                .filter(e -> byId.containsKey(e.getKey()))
                .map(e -> new PaperHit(byId.get(e.getKey()), e.getValue())).distinct().limit(4).toList();
        return new Llm.Out<>(new Match(known.contains(dup) ? dup : null, related, repoHits, paperHits), r.costUsd());
    }

    static final String TABLE_RULE = "|------|------|-------|------|--------|---------|";

    public static Filed file(String root, Project.Brief b, Idea idea, String goal, List<Idea> lineage, String projectPath, String date)
            throws IOException {
        return file(root, b, idea, goal, lineage, projectPath, date, Match.NONE);
    }

    public static Filed file(String root, Project.Brief b, Idea idea, String goal, List<Idea> lineage, String projectPath, String date, Match match)
            throws IOException {
        Path ws = Path.of(Project.expandHome(root)).normalize();
        Path index = ws.resolve("INDEX.md");
        if (!ws.isAbsolute() || !Files.isRegularFile(index)) {
            throw new IOException("Not an idea workspace (no INDEX.md): " + ws);
        }
        String slug = Project.slugify(b.slug());
        Path dir = ws.resolve("ideas").resolve(slug);
        String indexText = Files.readString(index);
        if (Files.exists(dir) || indexText.contains("](ideas/" + slug + "/")) return new Filed(dir.toString(), true, List.of());
        if (match.duplicateOf() != null) return new Filed(ws.resolve("ideas").resolve(match.duplicateOf()).toString(), true, List.of());
        int rule = indexText.indexOf(TABLE_RULE);
        if (rule < 0) throw new IOException("INDEX.md has no idea table");

        Files.createDirectories(dir.getParent());
        try {
            Files.createDirectory(dir); // not recursive: never write into an existing idea
        } catch (FileAlreadyExistsException e) {
            return new Filed(dir.toString(), true, List.of());
        }
        Files.writeString(dir.resolve("PITCH.md"), pitch(b, idea, goal, lineage, projectPath, date) + priorWork(match.papers()));
        if (!match.repos().isEmpty()) Files.writeString(dir.resolve("EXISTING-ASSETS.md"), existingAssets(b, match.repos(), date));
        Map<String, String> titles = rows(indexText).stream().collect(Collectors.toMap(Row::slug, Row::title, (x, y) -> x));
        Files.writeString(dir.resolve("LINK.md"), "# Links — " + b.name() + "\n\n" + (match.related().isEmpty() ? "None yet.\n"
                : match.related().entrySet().stream().map(e -> link(titles.get(e.getKey()), e.getKey(), e.getValue())).collect(Collectors.joining())));
        // Links are bidirectional: add the way back in each related idea.
        for (var e : match.related().entrySet()) addLink(ws.resolve("ideas").resolve(e.getKey()).resolve("LINK.md"), link(b.name(), slug, e.getValue()));
        int at = rule + TABLE_RULE.length() + 1;
        String row = "| " + date + " | [" + slug + "](ideas/" + slug + "/PITCH.md) | " + cell(b.name()) + " | "
                + cell(String.join(", ", idea.keywords())) + " | raw | " + cell(b.oneLiner()) + " |\n";
        Files.writeString(index, indexText.substring(0, Math.min(at, indexText.length())) + row + indexText.substring(Math.min(at, indexText.length())));
        return new Filed(dir.toString(), false, List.copyOf(match.related().keySet()));
    }

    /** Appended to PITCH.md: the papers the match kept, marked unverified. */
    static String priorWork(List<PaperHit> papers) {
        if (papers.isEmpty()) return "";
        return "\n## Prior work found (unverified)\n_(Hugging Face paper search, picked by the model; check before relying on it)_\n\n"
                + papers.stream().map(h -> "- [" + h.paper().title() + "](https://huggingface.co/papers/" + h.paper().id() + ")"
                        + (h.paper().published().length() >= 4 ? " (" + h.paper().published().substring(0, 4) + ")" : "") + " — " + h.why() + "\n")
                .collect(Collectors.joining());
    }

    static String existingAssets(Project.Brief b, List<RepoHit> repos, String date) {
        return "# Existing assets — " + b.name() + "\n\nRepos found on " + date + " by Idea Generator (model-picked, check them).\n\n"
                + "| Repo | Reusable for this idea |\n|---|---|\n"
                + repos.stream().map(h -> "| `" + h.path() + "` | " + h.why() + " |\n").collect(Collectors.joining())
                + "\nCheck who owns each repo (personal or employer) before reusing its code.\n";
    }

    static String link(String title, String slug, String why) {
        return "- [" + (title == null || title.isEmpty() ? slug : title) + "](../" + slug + "/PITCH.md) — " + why + "\n";
    }

    /** Append a link line to a LINK.md, replacing its "None yet." and creating it when missing. */
    static void addLink(Path linkMd, String line) throws IOException {
        if (!Files.isDirectory(linkMd.getParent())) return; // the idea is in the index but its folder is gone
        String text = Files.exists(linkMd) ? Files.readString(linkMd) : "# Links\n\n";
        if (text.contains(line.substring(0, line.indexOf(" — ")))) return;
        if (text.contains("None yet.\n")) text = text.replaceFirst("None yet\\.\n", java.util.regex.Matcher.quoteReplacement(line));
        else text = (text.endsWith("\n") ? text : text + "\n") + line;
        Files.writeString(linkMd, text);
    }

    /** One Markdown table cell: no pipes, no line breaks. */
    static String cell(String s) {
        return Js.trim(s == null ? "" : s).replace("|", "\\|").replaceAll("\\s*\\R\\s*", " ");
    }

    private static String bullets(List<String> xs) {
        return xs.isEmpty() ? "- (none)" : xs.stream().map(x -> "- " + x).collect(Collectors.joining("\n"));
    }

    static String pitch(Project.Brief b, Idea idea, String goal, List<Idea> lineage, String projectPath, String date) {
        Integer sum = Score.scoreSum(idea);
        List<String> why = new ArrayList<>();
        if (sum != null) {
            why.add("- Jury: originality " + idea.originality() + " · feasibility " + idea.feasibility() + " · impact " + idea.impact()
                    + " = " + sum + "/15" + Project.MEDAL[Math.max(0, Score.medal(idea))] + ". " + (idea.reason() == null ? "" : idea.reason()));
        }
        if (!Js.trim(goal).isEmpty()) why.add("- Board goal: " + Js.trim(goal));
        for (Idea i : lineage) why.add("- Grew out of (" + i.kind() + "): " + i.title());
        why.add("- Claude Code project: `" + projectPath + "`");
        return String.join("\n",
                "# " + b.name(), "",
                "> " + b.oneLiner(), "",
                "## Problem", "", b.problem(), "",
                "## Solution", "", Js.trim("**" + idea.title() + ".** " + (idea.pitch() == null ? "" : idea.pitch())), "",
                "MVP:", bullets(b.mvp()), "",
                "Suggested stack: " + b.stack(), "",
                "## Target users", "", b.audience(), "",
                "## Why now / why me", "", String.join("\n", why), "",
                "## Open questions", "", bullets(b.openQuestions()), "",
                "## Assumptions", "_(things not stated by Arthur, to validate)_", "",
                "- Everything above was generated by Idea Generator, not written by Arthur.",
                b.risks().stream().map(r -> "- Risk: " + r).collect(Collectors.joining("\n")), "",
                "---", "Created: " + date + " · Status: raw · Source: Idea Generator", "");
    }
}
