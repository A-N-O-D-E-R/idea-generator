package bio.anode.ideagenerator.repository;

import bio.anode.ideagenerator.Js;
import bio.anode.ideagenerator.model.Board;
import bio.anode.ideagenerator.model.Idea;
import bio.anode.ideagenerator.model.Reports.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Read models across all of a tenant's boards: keyword trends (dashboard) and the report, plus the saved synthesis. */
@Repository
@RequiredArgsConstructor
public class ReportRepository {
    private final JdbcClient db;
    private final BoardRepository boards;
    private final IdeaRepository ideas;
    private final SpendRepository spend;

    /**
     * Keyword trends across the tenant's boards. Per keyword: how many ideas, on how many
     * boards, mean jury sum, ideas in the last `days` vs the `days` before, and
     * a per-day count series for the last `days`.
     */
    public Dashboard dashboard(String t, int days) {
        String own = IdeaRepository.OWN_IDEAS;
        List<Map<String, Object>> rows = db.sql("""
                        SELECT k.keyword,
                           COUNT(*) AS count,
                           COUNT(DISTINCT i.board_id) AS boards,
                           AVG(i.originality + i.feasibility + i.impact) AS mean_sum,
                           COUNT(*) FILTER (WHERE i.created_at >= utc_text(?)) AS recent,
                           COUNT(*) FILTER (WHERE i.created_at < utc_text(?) AND i.created_at >= utc_text(?)) AS previous
                         FROM idea_keywords k\s""" + own + """
                         GROUP BY k.keyword
                         ORDER BY recent DESC, count DESC, k.keyword
                         LIMIT ?""")
                .params(days, days, days * 2, t, 40).query().listOfRows();
        List<Map<String, Object>> seriesRows = db.sql("""
                        SELECT k.keyword, substr(i.created_at, 1, 10) AS day, COUNT(*) AS n
                         FROM idea_keywords k\s""" + own + """
                         WHERE i.created_at >= utc_text(?)
                         GROUP BY k.keyword, day""")
                .params(t, days).query().listOfRows();
        List<String> dayList = new ArrayList<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (int d = days - 1; d >= 0; d--) dayList.add(today.minusDays(d).toString());
        Map<String, int[]> series = new LinkedHashMap<>();
        for (Map<String, Object> r : seriesRows) {
            int[] s = series.computeIfAbsent((String) r.get("keyword"), k -> new int[dayList.size()]);
            int at = dayList.indexOf((String) r.get("day"));
            if (at >= 0) s[at] = ((Number) r.get("n")).intValue();
        }
        Map<Long, BoardKeywords> perBoard = new LinkedHashMap<>();
        db.sql("""
                        SELECT b.id, b.title, k.keyword, COUNT(*) AS n
                         FROM boards b JOIN ideas i ON i.board_id = b.id JOIN idea_keywords k ON k.idea_id = i.id
                         WHERE b.tenant_id = ?
                         GROUP BY b.id, k.keyword ORDER BY b.id, n DESC, k.keyword""")
                .param(t)
                .query(rs -> {
                    BoardKeywords pb = perBoard.computeIfAbsent(rs.getLong("id"),
                            id -> new BoardKeywords(id, title(rs), new ArrayList<>()));
                    if (pb.keywords().size() < 6) pb.keywords().add(new KeywordCount(rs.getString("keyword"), rs.getLong("n")));
                });
        DashboardTotals totals = db.sql("""
                        SELECT (SELECT COUNT(*) FROM ideas WHERE\s""" + Rows.OWN + """
                        ) AS ideas, (SELECT COUNT(*) FROM boards WHERE tenant_id = ?) AS boards,
                                (SELECT COUNT(DISTINCT keyword) FROM idea_keywords k\s""" + own + """
                        ) AS keywords,
                                (SELECT COUNT(*) FROM ideas WHERE created_at >= utc_text(?) AND\s""" + Rows.OWN + ") AS recent")
                .params(t, t, t, days, t)
                .query((r, n) -> new DashboardTotals(r.getLong("ideas"), r.getLong("boards"), r.getLong("keywords"), r.getLong("recent")))
                .single();
        List<KeywordTrend> keywords = rows.stream().map(r -> {
            Number mean = (Number) r.get("mean_sum");
            String kw = (String) r.get("keyword");
            return new KeywordTrend(kw, num(r.get("count")), num(r.get("boards")),
                    mean == null ? null : Math.round(mean.doubleValue() * 10) / 10.0, num(r.get("recent")), num(r.get("previous")),
                    series.getOrDefault(kw, new int[dayList.size()]));
        }).toList();
        return new Dashboard(dayList, totals, keywords, new ArrayList<>(perBoard.values()));
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static String title(ResultSet rs) {
        try {
            return rs.getString("title");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Everything the report window shows for the last `days` days: totals,
     * every Claude Code project, and per board its goal, summary, best ideas.
     */
    public Report report(String t, int days) {
        List<Board> allBoards = boards.list(t);
        List<Idea> all = ideas.listAll(t);
        Set<Long> recentIds = new LinkedHashSet<>(db.sql("SELECT id FROM ideas WHERE created_at >= utc_text(?) AND " + Rows.OWN)
                .params(days, t).query(Long.class).list());
        Map<Long, String> titles = new LinkedHashMap<>();
        for (Board b : allBoards) titles.put(b.id(), b.title());
        Comparator<Idea> rank = (x, y) -> {
            int c = Integer.compare(sumOr(y), sumOr(x));
            return c != 0 ? c : Long.compare(y.id(), x.id());
        };
        List<ProjectRef> projects = all.stream().filter(i -> i.projectPath() != null && !i.projectPath().isEmpty())
                .map(i -> new ProjectRef(i.id(), i.boardId(), i.title(), i.pitch(), sum(i), medalOf(i), i.reason(), i.kind(),
                        titles.getOrDefault(i.boardId(), ""), i.projectPath()))
                .toList();
        int gold = 0, silver = 0, bronze = 0;
        for (Idea i : all) {
            Integer m = medalOf(i);
            if (m == null) continue;
            if (m == 3) gold++;
            else if (m == 2) silver++;
            else if (m == 1) bronze++;
        }
        double spent = spend.lastDays(t, days);
        List<ReportBoard> perBoard = allBoards.stream().map(b -> {
            List<Idea> own = all.stream().filter(i -> i.boardId() == b.id()).toList();
            ObjectNode summary = null;
            if (b.summary() != null) {
                summary = b.summary().deepCopy();
                summary.put("stale", b.summary().path("ideaCount").asDouble(-1) != own.size());
            }
            return new ReportBoard(b.id(), b.title(), b.goal(), b.config().mode(), own.size(),
                    (int) own.stream().filter(i -> recentIds.contains(i.id())).count(),
                    own.stream().filter(i -> sum(i) != null || i.medalOverride() != null).sorted(rank).limit(3).map(ReportRepository::brief).toList(),
                    summary, projects.stream().filter(p -> p.boardId() == b.id()).toList());
        }).toList();
        return new Report(days,
                new Totals(allBoards.size(), all.size(), recentIds.size(), (int) all.stream().filter(i -> i.judgedAt() != null).count(),
                        new Medals(gold, silver, bronze), projects.size(), Math.round(spent * 10000) / 10000.0),
                projects, perBoard, latestSynthesis(t));
    }

    private ObjectNode latestSynthesis(String t) {
        return db.sql("SELECT * FROM reports WHERE tenant_id = ? ORDER BY id DESC LIMIT 1").param(t).query((r, n) -> {
            JsonNode c = Rows.json(r.getString("content"));
            ObjectNode o = c instanceof ObjectNode on ? on : Js.object();
            o.put("days", r.getInt("days"));
            o.put("lang", r.getString("lang"));
            o.put("createdAt", r.getString("created_at"));
            return o;
        }).optional().orElse(null);
    }

    public void saveSynthesis(String t, int days, String lang, ObjectNode content) {
        db.sql("INSERT INTO reports (tenant_id, days, lang, content) VALUES (?, ?, ?, ?)").params(t, days, lang, Js.stringify(content)).update();
    }

    // The report's own scoring, verbatim from db.ts (null-checks originality only).
    private static Integer sum(Idea i) {
        return i.originality() == null ? null : i.originality() + i.feasibility() + i.impact();
    }

    private static int sumOr(Idea i) {
        Integer s = sum(i);
        return s == null ? -1 : s;
    }

    private static Integer medalOf(Idea i) {
        if (i.medalOverride() != null) return i.medalOverride();
        Integer s = sum(i);
        return s == null ? null : s >= 13 ? 3 : s >= 11 ? 2 : s >= 9 ? 1 : 0;
    }

    private static Brief brief(Idea i) {
        return new Brief(i.id(), i.boardId(), i.title(), i.pitch(), sum(i), medalOf(i), i.reason(), i.kind());
    }
}
