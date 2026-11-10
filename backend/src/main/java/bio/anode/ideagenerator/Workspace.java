package bio.anode.ideagenerator;

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

    /** The judge of "have we had this before": a slug it duplicates, and slugs it relates to, each with a reason. */
    public record Match(String duplicateOf, Map<String, String> related) {
        public static final Match NONE = new Match(null, Map.of());
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

    static final JsonNode MATCH_SCHEMA = Js.parse("""
            {"type":"object","properties":{
              "duplicateOf":{"type":"string","description":"Slug of an existing idea that is the same idea (same problem AND same mechanism), or empty string if none."},
              "related":{"type":"array","description":"0–4 existing ideas that are close but different.","items":{"type":"object","properties":{
                "slug":{"type":"string"},
                "why":{"type":"string","description":"One short line: how they relate (shares tech / same market / could merge / alternative to...)."}},
                "required":["slug","why"],"additionalProperties":false}}},
             "required":["duplicateOf","related"],"additionalProperties":false}""");

    /** One LLM call against the index. Only slugs that are really in the index survive. */
    public static Llm.Out<Match> match(Chat chat, String model, Project.Brief b, Idea idea, List<Row> rows) {
        if (rows.isEmpty()) return new Llm.Out<>(Match.NONE, 0);
        String list = rows.stream().map(r -> "- " + r.slug() + ": " + r.title() + " [" + r.tags() + "] " + r.summary()).collect(Collectors.joining("\n"));
        ChatResult r = chat.chat(new ChatRequest(model,
                "You keep an index of ideas tidy. You are strict about duplicates: only the same problem solved by the same mechanism is a duplicate. A shared topic is at most related.",
                "New idea:\n" + b.name() + ": " + b.oneLiner() + "\n" + Llm.fmtIdea(idea.title(), idea.pitch())
                        + "\n\nExisting ideas:\n" + list + "\n\nIs the new idea a duplicate of one of them, and which are related?",
                "workspace_match", MATCH_SCHEMA, 0.2, 800, null));
        JsonNode v = Js.absent(r.value()) ? Js.object() : r.value();
        java.util.Set<String> known = rows.stream().map(Row::slug).collect(Collectors.toSet());
        String dup = Js.trim(Js.str(v.path("duplicateOf")));
        Map<String, String> related = new LinkedHashMap<>();
        for (JsonNode x : Js.arr(v.path("related"))) {
            String slug = Js.trim(Js.str(x.path("slug")));
            if (known.contains(slug) && !slug.equals(dup) && related.size() < 4) related.put(slug, cell(Js.str(x.path("why"))));
        }
        return new Llm.Out<>(new Match(known.contains(dup) ? dup : null, related), r.costUsd());
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
        Files.writeString(dir.resolve("PITCH.md"), pitch(b, idea, goal, lineage, projectPath, date));
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
