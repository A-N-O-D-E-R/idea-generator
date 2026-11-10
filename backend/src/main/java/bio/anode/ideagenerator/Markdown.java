package bio.anode.ideagenerator;

import bio.anode.ideagenerator.Idea.Edge;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The Markdown exports: one board (export.md) and the cross-board report (report.md). */
public final class Markdown {
    private Markdown() {}

    record Labels(String flow, String ideas, String exported, String seed, String source, String parents, String originality,
                  String feasibility, String impact, String unjudged, String handset, String guest, String project, String summary,
                  String strongest, String gaps, String next, String stale) {}

    static final Map<String, Labels> LABELS = Map.of(
            "sv", new Labels("Flöde", "idéer", "Exporterad", "Frö", "Källa", "Föräldrar", "Originalitet", "Genomförbarhet", "Effekt",
                    "Ej bedömd", "satt för hand", "Gäst från tavlan", "Claude Code-projekt", "Sammanfattning", "Starkast", "Luckor",
                    "Nästa steg", "Sammanfattningen är äldre än tavlan"),
            "en", new Labels("Flow", "ideas", "Exported", "Seed", "Source", "Parents", "Originality", "Feasibility", "Impact",
                    "Not judged", "set by hand", "Guest from board", "Claude Code project", "Summary", "Strongest", "Gaps",
                    "Next steps", "The summary is older than the board"));

    static final String[] MEDAL_ICON = {"", "🥉 ", "🥈 ", "🥇 "};

    private static String icon(Integer m) {
        return m != null && m >= 0 && m < MEDAL_ICON.length ? MEDAL_ICON[m] : "";
    }

    private static boolean has(String s) {
        return s != null && !s.isEmpty();
    }

    public static String board(Board board, List<Idea> ideas, List<Edge> edges, String lang) {
        Labels l = LABELS.get(lang);
        Map<Long, Idea> byId = new HashMap<>();
        for (Idea i : ideas) byId.put(i.id(), i);
        Map<Long, List<Idea>> parents = new LinkedHashMap<>();
        for (Edge e : edges) {
            Idea p = byId.get(e.from());
            if (p != null) parents.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(p);
        }
        List<Idea> sorted = ideas.stream().sorted(Comparator.<Idea>comparingInt(i -> {
            Integer f = Score.fitness(i);
            return f == null ? -1 : f;
        }).reversed().thenComparingLong(Idea::id)).toList();
        List<String> out = new ArrayList<>(List.of("# " + board.title(), ""));
        if (has(board.goal())) out.addAll(List.of("> " + board.goal(), ""));
        out.addAll(List.of(l.flow() + ": " + board.config().flowName() + " · " + ideas.size() + " " + l.ideas() + " · " + l.exported() + " " + Js.utcToday(), ""));
        ObjectNode s = board.summary();
        if (s != null) {
            out.addAll(List.of("## " + l.summary(), "", Js.str(s.path("headline")), ""));
            for (JsonNode t : Js.arr(s.path("themes"))) out.addAll(List.of("**" + Js.str(t.path("name")) + ".** " + Js.str(t.path("insight")), ""));
            List<JsonNode> strongest = Js.arr(s.path("strongest"));
            if (!strongest.isEmpty()) {
                out.add("**" + l.strongest() + ":**");
                strongest.forEach(x -> out.add("- " + Js.str(x.path("title")) + ": " + Js.str(x.path("why"))));
                out.add("");
            }
            bulletBlock(out, "**" + l.gaps() + ":**", Js.arr(s.path("gaps")));
            bulletBlock(out, "**" + l.next() + ":**", Js.arr(s.path("nextSteps")));
            double count = Js.num(s.path("ideaCount"));
            if (count != ideas.size()) {
                out.addAll(List.of("*" + l.stale() + " (" + Js.str(s.path("ideaCount")) + " → " + ideas.size() + " " + l.ideas() + ")*", ""));
            }
            out.addAll(List.of("---", ""));
        }
        for (Idea i : sorted) {
            Integer m = Score.medal(i);
            Integer sum = Score.scoreSum(i);
            out.addAll(List.of("## " + (m != null && m != 0 ? icon(m) : "") + i.title() + (sum != null ? " — " + sum + "/15" : ""), ""));
            if (has(i.pitch())) out.addAll(List.of(i.pitch(), ""));
            if (sum != null) {
                out.addAll(List.of(Js.trim("*" + l.originality() + " " + i.originality() + " · " + l.feasibility() + " " + i.feasibility()
                        + " · " + l.impact() + " " + i.impact() + ".* " + (i.reason() == null ? "" : i.reason())), ""));
            } else if (i.medalOverride() == null) {
                out.addAll(List.of("*" + l.unjudged() + "*", ""));
            }
            if (i.medalOverride() != null) {
                String ic = icon(i.medalOverride());
                out.addAll(List.of("*" + (ic.isEmpty() ? "–" : ic) + " " + l.handset() + "*", ""));
            }
            if (!i.keywords().isEmpty()) out.addAll(List.of(i.keywords().stream().map(k -> "`" + k + "`").collect(Collectors.joining(" ")), ""));
            List<Idea> ps = parents.get(i.id());
            if (ps != null && !ps.isEmpty()) out.addAll(List.of(l.parents() + ": " + ps.stream().map(Idea::title).collect(Collectors.joining(" × ")), ""));
            else if ("guest".equals(i.kind())) out.addAll(List.of(l.guest() + " " + (i.originBoardTitle() != null ? i.originBoardTitle() : "?"), ""));
            else if (has(i.sourceUrl())) out.addAll(List.of(l.seed() + " · " + l.source() + ": " + i.sourceUrl(), ""));
            else out.addAll(List.of(l.seed(), ""));
            if (has(i.projectPath())) out.addAll(List.of(l.project() + ": `" + i.projectPath() + "`", ""));
        }
        return String.join("\n", out);
    }

    private static void bulletBlock(List<String> out, String head, List<JsonNode> xs) {
        if (xs.isEmpty()) return;
        out.add(head);
        xs.forEach(x -> out.add("- " + Js.str(x)));
        out.add("");
    }

    record ReportLabels(String title, String period, String boards, String ideas, String newIdeas, String projects, String spent,
                        String conclusions, String insights, String standouts, String opportunities, String next, String goal,
                        String best, String summary, String stale, String none) {}

    static final Map<String, ReportLabels> REPORT_LABELS = Map.of(
            "sv", new ReportLabels("Rapport", "Senaste {n} dagarna", "tavlor", "idéer", "nya", "Claude Code-projekt", "Förbrukat",
                    "Slutsatser", "Insikter", "Mest värt att driva vidare", "Möjligheter", "Nästa steg", "Mål", "Bästa idéerna",
                    "Sammanfattning", "äldre än tavlan", "Inga ännu."),
            "en", new ReportLabels("Report", "Last {n} days", "boards", "ideas", "new", "Claude Code projects", "Spent", "Conclusions",
                    "Insights", "Most worth pursuing", "Opportunities", "Next steps", "Goal", "Best ideas", "Summary",
                    "older than the board", "None yet."));

    private static void section(List<String> out, String head, List<String> lines) {
        if (lines.isEmpty()) return;
        out.addAll(List.of(head, ""));
        out.addAll(lines);
        out.add("");
    }

    public static String report(Repo.Report r, String lang) {
        ReportLabels l = REPORT_LABELS.get(lang);
        Repo.Medals m = r.totals().medals();
        List<String> out = new ArrayList<>(List.of("# " + l.title() + " · " + Js.utcToday(), "",
                l.period().replace("{n}", String.valueOf(r.days())) + " · " + r.totals().boards() + " " + l.boards() + " · "
                        + r.totals().ideas() + " " + l.ideas() + " (" + r.totals().newIdeas() + " " + l.newIdeas() + ") · 🥇 " + m.gold()
                        + " 🥈 " + m.silver() + " 🥉 " + m.bronze() + " · " + r.totals().projects() + " " + l.projects() + " · " + l.spent()
                        + " $" + String.format(Locale.ROOT, "%.2f", r.totals().spentUsd()), ""));
        ObjectNode s = r.synthesis();
        if (s != null) {
            out.addAll(List.of("## " + l.conclusions(), "", Js.str(s.path("headline")), ""));
            section(out, "### " + l.insights(), Js.arr(s.path("insights")).stream().map(x -> "- " + Js.str(x)).toList());
            section(out, "### " + l.standouts(), Js.arr(s.path("standouts")).stream()
                    .map(x -> "- **" + Js.str(x.path("title")) + "** (" + Js.str(x.path("board")) + "): " + Js.str(x.path("why"))).toList());
            section(out, "### " + l.opportunities(), Js.arr(s.path("opportunities")).stream().map(x -> "- " + Js.str(x)).toList());
            section(out, "### " + l.next(), Js.arr(s.path("nextSteps")).stream().map(x -> "- " + Js.str(x)).toList());
        }
        out.addAll(List.of("## " + l.projects(), ""));
        if (r.projects().isEmpty()) out.addAll(List.of(l.none(), ""));
        for (Repo.ProjectRef p : r.projects()) {
            out.add("- **" + p.title() + "** (" + p.boardTitle() + (p.sum() != null ? ", " + p.sum() + "/15" : "") + "): `cd \"" + p.path() + "\" && claude`");
        }
        out.add("");
        for (Repo.ReportBoard b : r.boards()) {
            out.addAll(List.of("## " + b.title(), ""));
            if (has(b.goal())) out.addAll(List.of("> " + l.goal() + ": " + b.goal(), ""));
            out.addAll(List.of(b.ideaCount() + " " + l.ideas() + " (" + b.newIdeas() + " " + l.newIdeas() + ")", ""));
            if (b.summary() != null) {
                out.addAll(List.of("**" + l.summary() + (b.summary().path("stale").asBoolean(false) ? " (" + l.stale() + ")" : "") + ":** "
                        + Js.str(b.summary().path("headline")), ""));
            }
            if (!b.best().isEmpty()) {
                out.add("**" + l.best() + ":**");
                b.best().forEach(i -> out.add("- " + icon(i.medal() == null ? 0 : i.medal()) + i.title() + (i.sum() != null ? " — " + i.sum() + "/15" : "")));
                out.add("");
            }
        }
        return String.join("\n", out);
    }
}
