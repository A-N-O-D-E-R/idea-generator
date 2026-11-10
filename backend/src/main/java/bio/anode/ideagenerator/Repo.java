package bio.anode.ideagenerator;

import bio.anode.ideagenerator.Board.Template;
import bio.anode.ideagenerator.Idea.Edge;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Postgres (schema in db/migration, owned by Flyway), ported from upstream db.ts:
 * same tables, same JSON-in-column choices, same queries, plus tenancy.
 *
 * Every query is scoped by tenant: a row of another tenant is never read,
 * changed or deleted, it is simply not found. Ideas, edges, leads, keywords
 * and spend hang off boards, so their queries check the board's tenant.
 * Every method is synchronized, as upstream ran on one synchronous connection.
 * ponytail: one global lock; per-tenant locks or none if throughput ever matters.
 */
public class Repo {
    /** The one tenant when ideagen.auth=none. */
    public static final String LOCAL = "local";

    /** The boards of tenant ? (a SQL fragment: "board_id IN (...)"). */
    static final String OWN = "board_id IN (SELECT id FROM boards WHERE tenant_id = ?) ";

    public record Lead(long id, long boardId, String source, String title, String summary, String hook, String url,
                       JsonNode meta, Long ideaId, String fetchedAt) {}

    public record LeadDraft(String title, String summary, String hook, String url, Map<String, Object> meta) {}

    public record Spinoff(long ideaId, long boardId, String title) {}

    public record Spent(double usd, long calls) {}

    // ---- dashboard
    public record KeywordTrend(String keyword, long count, long boards, Double meanSum, long recent, long previous, int[] series) {}

    public record KeywordCount(String keyword, long count) {}

    public record BoardKeywords(long id, String title, List<KeywordCount> keywords) {}

    public record DashboardTotals(long ideas, long boards, long keywords, long recent) {}

    public record Dashboard(List<String> days, DashboardTotals totals, List<KeywordTrend> keywords, List<BoardKeywords> boards) {}

    // ---- report
    public record Brief(long id, long boardId, String title, String pitch, Integer sum, Integer medal, String reason, String kind) {}

    public record ProjectRef(long id, long boardId, String title, String pitch, Integer sum, Integer medal, String reason,
                             String kind, String boardTitle, String path) {}

    public record Medals(int gold, int silver, int bronze) {}

    public record Totals(int boards, int ideas, int newIdeas, int judged, Medals medals, int projects, double spentUsd) {}

    public record ReportBoard(long id, String title, String goal, String mode, int ideaCount, int newIdeas, List<Brief> best,
                              ObjectNode summary, List<ProjectRef> projects) {}

    public record Report(int days, Totals totals, List<ProjectRef> projects, List<ReportBoard> boards, ObjectNode synthesis) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;

    public Repo(DataSource ds) {
        this.db = JdbcClient.create(ds);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    private long count(String sql, Object... params) {
        return db.sql(sql).params(params).query(Long.class).single();
    }

    private <T> T inTx(Supplier<T> fn) {
        return tx.execute(s -> fn.get());
    }

    // ---------------------------------------------------------------- tenants

    /** Create the tenant if new, with the default templates. */
    public synchronized void ensureTenant(String id, String name, String email) {
        inTx(() -> {
            int created = db.sql("INSERT INTO tenants (id, name, email) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
                    .params(id, name == null ? "" : name, email).update();
            if (created > 0 || count("SELECT COUNT(*) FROM templates WHERE tenant_id = ?", id) == 0) {
                for (Template t : Board.DEFAULT_TEMPLATES) {
                    db.sql("INSERT INTO templates (tenant_id, name, system, expand, combine, sort) VALUES (?, ?, ?, ?, ?, ?)")
                            .params(id, t.name(), t.system(), t.expand(), t.combine(), t.sort()).update();
                }
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- row mapping

    static final String IDEA_SELECT = """
            SELECT i.*, ob.title AS origin_board_title,
              (SELECT string_agg(keyword, '|' ORDER BY keyword) FROM idea_keywords k WHERE k.idea_id = i.id) AS keywords
              FROM ideas i JOIN boards tb ON tb.id = i.board_id LEFT JOIN boards ob ON ob.id = i.origin_board_id""";

    static final String BOARD_SELECT = """
            SELECT b.*, oi.title AS origin_idea_title, oi.board_id AS origin_board_id, ob.title AS origin_board_title
              FROM boards b LEFT JOIN ideas oi ON oi.id = b.origin_idea_id LEFT JOIN boards ob ON ob.id = oi.board_id""";

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Double doubleOrNull(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static JsonNode parseJson(String s) {
        try {
            return Js.parse(s);
        } catch (RuntimeException e) {
            return Js.object();
        }
    }

    static final RowMapper<Idea> IDEA = (r, n) -> {
        String kw = r.getString("keywords");
        return new Idea(r.getLong("id"), r.getLong("board_id"), r.getString("title"), r.getString("pitch"), r.getString("kind"),
                r.getString("source_url"), r.getDouble("x"), r.getDouble("y"), intOrNull(r, "originality"),
                intOrNull(r, "feasibility"), intOrNull(r, "impact"), r.getString("reason"), r.getString("judged_at"),
                intOrNull(r, "medal_override"), r.getInt("generation"), r.getString("project_path"),
                longOrNull(r, "origin_idea_id"), longOrNull(r, "origin_board_id"), r.getString("origin_board_title"),
                kw == null || kw.isEmpty() ? List.of() : Arrays.asList(kw.split("\\|")), r.getString("created_at"),
                doubleOrNull(r, "elo"), r.getInt("duels"));
    };

    static final RowMapper<Board> BOARD = (r, n) -> {
        String summary = r.getString("summary");
        JsonNode s = summary == null || summary.isEmpty() ? null : parseJson(summary);
        return new Board(r.getLong("id"), r.getString("title"), r.getString("goal"),
                Board.mergeConfig(Board.defaultConfig(), parseJson(r.getString("config"))),
                r.getString("created_at"), r.getString("updated_at"), longOrNull(r, "origin_idea_id"),
                r.getString("origin_idea_title"), longOrNull(r, "origin_board_id"), r.getString("origin_board_title"),
                s instanceof ObjectNode o ? o : s == null ? null : Js.object(), r.getString("tenant_id"));
    };

    static final RowMapper<Lead> LEAD = (r, n) -> new Lead(r.getLong("id"), r.getLong("board_id"), r.getString("source"),
            r.getString("title"), r.getString("summary"), r.getString("hook"), r.getString("url"), parseJson(r.getString("meta")),
            longOrNull(r, "idea_id"), r.getString("fetched_at"));

    static final RowMapper<Template> TEMPLATE = (r, n) -> new Template(r.getLong("id"), r.getString("name"),
            r.getString("system"), r.getString("expand"), r.getString("combine"), r.getInt("sort"));

    // ---------------------------------------------------------------- boards

    /** Boards, newest activity first, each with its ideaCount. */
    public synchronized List<ObjectNode> listBoards(String t) {
        return boards(t).stream().map(b -> {
            ObjectNode n = Js.tree(b);
            n.put("ideaCount", count("SELECT COUNT(*) FROM ideas WHERE board_id = ?", b.id()));
            return n;
        }).toList();
    }

    private List<Board> boards(String t) {
        return db.sql("SELECT * FROM (" + BOARD_SELECT + " WHERE b.tenant_id = ?) b2 ORDER BY b2.updated_at DESC, b2.id DESC")
                .param(t).query(BOARD).list();
    }

    /** Every tenant's boards in automatic mode, for the autopilot (the one cross-tenant read). */
    public synchronized List<Board> autoBoards() {
        return db.sql(BOARD_SELECT + " ORDER BY b.id").query(BOARD).list().stream().filter(b -> "auto".equals(b.config().mode())).toList();
    }

    public synchronized Board getBoard(String t, long id) {
        return db.sql(BOARD_SELECT + " WHERE b.id = ? AND b.tenant_id = ?").params(id, t).query(BOARD).optional().orElse(null);
    }

    public synchronized Board createBoard(String t, String title, String goal, Long templateId, JsonNode config, Long originIdeaId) {
        Board.Config c = Board.mergeConfig(Board.defaultConfig(getTemplate(t, templateId)), config);
        // A spin-off's origin must be one of the tenant's own ideas.
        Long origin = originIdeaId != null && getIdea(t, originIdeaId) != null ? originIdeaId : null;
        long id = db.sql("INSERT INTO boards (tenant_id, title, goal, config, origin_idea_id) VALUES (?, ?, ?, ?, ?) RETURNING id")
                .params(t, title, goal == null ? "" : goal, Js.stringify(c), origin).query(Long.class).single();
        return getBoard(t, id);
    }

    public Board createBoard(String t, String title, JsonNode config) {
        return createBoard(t, title, "", null, config, null);
    }

    /** title/goal null = unchanged; config null = unchanged, otherwise merged onto the current one. */
    public synchronized Board updateBoard(String t, long id, String title, String goal, JsonNode config) {
        Board cur = getBoard(t, id);
        if (cur == null) return null;
        Board.Config c = config == null ? cur.config() : Board.mergeConfig(cur.config(), config);
        db.sql("UPDATE boards SET title = ?, goal = ?, config = ?, updated_at = utc_text() WHERE id = ? AND tenant_id = ?")
                .params(title != null ? title : cur.title(), goal != null ? goal : cur.goal(), Js.stringify(c), id, t).update();
        return getBoard(t, id);
    }

    public synchronized Board setSummary(String t, long id, ObjectNode summary) {
        db.sql("UPDATE boards SET summary = ? WHERE id = ? AND tenant_id = ?").params(Js.stringify(summary), id, t).update();
        return getBoard(t, id);
    }

    private void touchBoard(String t, long id) {
        db.sql("UPDATE boards SET updated_at = utc_text() WHERE id = ? AND tenant_id = ?").params(id, t).update();
    }

    /** Boards spun off from ideas on this board. */
    public synchronized List<Spinoff> spinoffs(String t, long boardId) {
        return db.sql("""
                        SELECT b.id AS board_id, b.title, b.origin_idea_id FROM boards b JOIN ideas i ON i.id = b.origin_idea_id
                         WHERE i.board_id = ? AND b.tenant_id = ? ORDER BY b.id""")
                .params(boardId, t)
                .query((r, n) -> new Spinoff(r.getLong("origin_idea_id"), r.getLong("board_id"), r.getString("title"))).list();
    }

    public synchronized boolean deleteBoard(String t, long id) {
        return db.sql("DELETE FROM boards WHERE id = ? AND tenant_id = ?").params(id, t).update() > 0;
    }

    // ---------------------------------------------------------------- ideas

    public synchronized List<Idea> listIdeas(String t, long boardId) {
        return db.sql(IDEA_SELECT + " WHERE i.board_id = ? AND tb.tenant_id = ? ORDER BY i.id").params(boardId, t).query(IDEA).list();
    }

    public synchronized Idea getIdea(String t, long id) {
        return db.sql(IDEA_SELECT + " WHERE i.id = ? AND tb.tenant_id = ?").params(id, t).query(IDEA).optional().orElse(null);
    }

    public record NewIdea(long boardId, String title, String pitch, String kind, String sourceUrl, double x, double y,
                          int generation, Long originIdeaId, Long originBoardId) {
        public NewIdea(long boardId, String title, String pitch, String kind, String sourceUrl, double x, double y, int generation) {
            this(boardId, title, pitch, kind, sourceUrl, x, y, generation, null, null);
        }
    }

    /** Insert an idea and the edges from its parents (ideas of the same board) in one transaction. */
    public synchronized Idea insertIdea(String t, NewIdea i, List<Long> parentIds) {
        return inTx(() -> {
            if (count("SELECT COUNT(*) FROM boards WHERE id = ? AND tenant_id = ?", i.boardId(), t) == 0) {
                throw new AppError(404, "not_found", "Board not found");
            }
            long id = db.sql("""
                            INSERT INTO ideas (board_id, title, pitch, kind, source_url, x, y, generation, origin_idea_id, origin_board_id)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                    .params(i.boardId(), i.title(), i.pitch(), i.kind(), i.sourceUrl(), i.x(), i.y(), i.generation(),
                            i.originIdeaId(), i.originBoardId())
                    .query(Long.class).single();
            for (long p : parentIds) {
                db.sql("""
                                INSERT INTO edges (board_id, from_id, to_id) SELECT ?, ?, ?
                                 WHERE EXISTS (SELECT 1 FROM ideas WHERE id = ? AND board_id = ?) ON CONFLICT DO NOTHING""")
                        .params(i.boardId(), p, id, p, i.boardId()).update();
            }
            touchBoard(t, i.boardId());
            return getIdea(t, id);
        });
    }

    public Idea insertIdea(String t, NewIdea i) {
        return insertIdea(t, i, List.of());
    }

    /** Is this original already a guest on that board? */
    public synchronized boolean hasGuest(String t, long boardId, long originIdeaId) {
        return count("SELECT COUNT(*) FROM ideas WHERE board_id = ? AND (origin_idea_id = ? OR id = ?) AND " + OWN,
                boardId, originIdeaId, originIdeaId, t) > 0;
    }

    public synchronized Idea saveJudgement(String t, long id, Llm.Judgement j) {
        db.sql("UPDATE ideas SET originality = ?, feasibility = ?, impact = ?, reason = ?, judged_at = utc_text() WHERE id = ? AND " + OWN)
                .params(j.originality(), j.feasibility(), j.impact(), j.reason(), id, t).update();
        return getIdea(t, id);
    }

    /** Store both sides of a duel at once. */
    public synchronized void saveDuel(String t, long a, double eloA, long b, double eloB) {
        inTx(() -> {
            db.sql("UPDATE ideas SET elo = ?, duels = duels + 1 WHERE id = ? AND " + OWN).params(eloA, a, t).update();
            db.sql("UPDATE ideas SET elo = ?, duels = duels + 1 WHERE id = ? AND " + OWN).params(eloB, b, t).update();
            return null;
        });
    }

    public synchronized Idea setKeywords(String t, long id, List<String> keywords) {
        if (getIdea(t, id) == null) return null;
        inTx(() -> {
            db.sql("DELETE FROM idea_keywords WHERE idea_id = ?").param(id).update();
            for (String k : keywords) db.sql("INSERT INTO idea_keywords (idea_id, keyword) VALUES (?, ?) ON CONFLICT DO NOTHING").params(id, k).update();
            return null;
        });
        return getIdea(t, id);
    }

    private static final String OWN_IDEAS = "JOIN ideas i ON i.id = k.idea_id JOIN boards b ON b.id = i.board_id AND b.tenant_id = ? ";

    /** The tenant's keywords in use, most common first, so the judge can reuse them. */
    public synchronized List<String> keywordVocabulary(String t) {
        return db.sql("SELECT keyword, COUNT(*) AS n FROM idea_keywords k " + OWN_IDEAS + " GROUP BY keyword ORDER BY n DESC, keyword LIMIT ?")
                .params(t, 80).query((r, n) -> r.getString("keyword")).list();
    }

    /**
     * Keyword trends across the tenant's boards. Per keyword: how many ideas, on how many
     * boards, mean jury sum, ideas in the last `days` vs the `days` before, and
     * a per-day count series for the last `days`.
     */
    public synchronized Dashboard dashboard(String t, int days) {
        List<Map<String, Object>> rows = db.sql("""
                        SELECT k.keyword,
                           COUNT(*) AS count,
                           COUNT(DISTINCT i.board_id) AS boards,
                           AVG(i.originality + i.feasibility + i.impact) AS mean_sum,
                           COUNT(*) FILTER (WHERE i.created_at >= utc_text(?)) AS recent,
                           COUNT(*) FILTER (WHERE i.created_at < utc_text(?) AND i.created_at >= utc_text(?)) AS previous
                         FROM idea_keywords k\s""" + OWN_IDEAS + """
                         GROUP BY k.keyword
                         ORDER BY recent DESC, count DESC, k.keyword
                         LIMIT ?""")
                .params(days, days, days * 2, t, 40).query().listOfRows();
        List<Map<String, Object>> seriesRows = db.sql("""
                        SELECT k.keyword, substr(i.created_at, 1, 10) AS day, COUNT(*) AS n
                         FROM idea_keywords k\s""" + OWN_IDEAS + """
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
                            id -> new BoardKeywords(id, uncheckedString(rs, "title"), new ArrayList<>()));
                    if (pb.keywords().size() < 6) pb.keywords().add(new KeywordCount(rs.getString("keyword"), rs.getLong("n")));
                });
        DashboardTotals totals = db.sql("""
                        SELECT (SELECT COUNT(*) FROM ideas WHERE\s""" + OWN + """
                        ) AS ideas, (SELECT COUNT(*) FROM boards WHERE tenant_id = ?) AS boards,
                                (SELECT COUNT(DISTINCT keyword) FROM idea_keywords k\s""" + OWN_IDEAS + """
                        ) AS keywords,
                                (SELECT COUNT(*) FROM ideas WHERE created_at >= utc_text(?) AND\s""" + OWN + ") AS recent")
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

    private static String uncheckedString(ResultSet rs, String col) {
        try {
            return rs.getString(col);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** As upstream: the plain idea row (no keywords, no originBoardTitle) plus boardTitle. */
    public synchronized List<ObjectNode> ideasWithKeyword(String t, String keyword) {
        return db.sql("""
                        SELECT i.*, b.title AS board_title, NULL AS origin_board_title, NULL AS keywords
                         FROM idea_keywords k\s""" + OWN_IDEAS + """
                         WHERE k.keyword = ? ORDER BY i.id DESC LIMIT ?""")
                .params(t, keyword, 100)
                .query((r, n) -> {
                    ObjectNode o = Js.tree(IDEA.mapRow(r, n));
                    o.put("boardTitle", r.getString("board_title"));
                    return o;
                }).list();
    }

    public synchronized Idea setMedalOverride(String t, long id, Integer medal) {
        db.sql("UPDATE ideas SET medal_override = ? WHERE id = ? AND " + OWN).params(medal, id, t).update();
        return getIdea(t, id);
    }

    public synchronized Idea setProjectPath(String t, long id, String path) {
        db.sql("UPDATE ideas SET project_path = ? WHERE id = ? AND " + OWN).params(path, id, t).update();
        return getIdea(t, id);
    }

    public record Position(long id, double x, double y) {}

    public synchronized void savePositions(String t, long boardId, List<Position> positions) {
        inTx(() -> {
            for (Position p : positions) {
                db.sql("UPDATE ideas SET x = ?, y = ? WHERE id = ? AND board_id = ? AND " + OWN).params(p.x(), p.y(), p.id(), boardId, t).update();
            }
            return null;
        });
    }

    public synchronized boolean deleteIdea(String t, long id) {
        return db.sql("DELETE FROM ideas WHERE id = ? AND " + OWN).params(id, t).update() > 0;
    }

    // ---------------------------------------------------------------- edges and lineage

    public synchronized List<Edge> listEdges(String t, long boardId) {
        return db.sql("SELECT from_id, to_id FROM edges WHERE board_id = ? AND " + OWN + " ORDER BY seq").params(boardId, t)
                .query((r, n) -> new Edge(r.getLong("from_id"), r.getLong("to_id"))).list();
    }

    public synchronized List<Idea> parentsOf(String t, long id) {
        return db.sql(IDEA_SELECT + " JOIN edges e ON i.id = e.from_id WHERE e.to_id = ? AND tb.tenant_id = ? ORDER BY i.id")
                .params(id, t).query(IDEA).list();
    }

    /** Ancestors, nearest first. A guest continues into its original's ancestry on the other board. */
    public synchronized List<Idea> lineage(String t, long id) {
        int max = 12;
        List<Idea> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>(Set.of(id));
        java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>(List.of(id));
        while (!queue.isEmpty() && out.size() < max) {
            Idea cur = getIdea(t, queue.poll());
            if (cur == null) continue;
            List<Idea> next = new ArrayList<>(parentsOf(t, cur.id()));
            if (cur.originIdeaId() != null) {
                Idea origin = getIdea(t, cur.originIdeaId());
                if (origin != null) next.add(origin);
            }
            for (Idea p : next) {
                if (!seen.add(p.id())) continue;
                out.add(p);
                queue.add(p.id());
            }
        }
        return out.size() > max ? out.subList(0, max) : out;
    }

    // ---------------------------------------------------------------- leads

    public synchronized List<Lead> listLeads(String t, long boardId) {
        return db.sql("SELECT * FROM leads WHERE board_id = ? AND " + OWN + " ORDER BY source, id").params(boardId, t).query(LEAD).list();
    }

    public synchronized Lead getLead(String t, long id) {
        return db.sql("SELECT * FROM leads WHERE id = ? AND " + OWN).params(id, t).query(LEAD).optional().orElse(null);
    }

    /** A refresh replaces the unused leads of that source; used ones stay as history. */
    public synchronized List<Lead> replaceLeads(String t, long boardId, String source, List<LeadDraft> drafts) {
        if (getBoard(t, boardId) == null) return List.of();
        inTx(() -> {
            db.sql("DELETE FROM leads WHERE board_id = ? AND source = ? AND idea_id IS NULL").params(boardId, source).update();
            for (LeadDraft d : drafts) {
                db.sql("INSERT INTO leads (board_id, source, title, summary, hook, url, meta) VALUES (?, ?, ?, ?, ?, ?, ?)")
                        .params(boardId, source, d.title(), d.summary(), d.hook(), d.url(),
                                Js.stringify(d.meta() == null ? Map.of() : d.meta())).update();
            }
            return null;
        });
        return listLeads(t, boardId).stream().filter(l -> l.source().equals(source)).toList();
    }

    public synchronized Lead markLeadUsed(String t, long id, long ideaId) {
        db.sql("UPDATE leads SET idea_id = ? WHERE id = ? AND " + OWN).params(ideaId, id, t).update();
        return getLead(t, id);
    }

    public synchronized boolean deleteLead(String t, long id) {
        return db.sql("DELETE FROM leads WHERE id = ? AND " + OWN).params(id, t).update() > 0;
    }

    // ---------------------------------------------------------------- templates

    public synchronized List<Template> listTemplates(String t) {
        return db.sql("SELECT * FROM templates WHERE tenant_id = ? ORDER BY sort, id").param(t).query(TEMPLATE).list();
    }

    /** The tenant's template with this id, or null. */
    public synchronized Template findTemplate(String t, long id) {
        return db.sql("SELECT * FROM templates WHERE id = ? AND tenant_id = ?").params(id, t).query(TEMPLATE).optional().orElse(null);
    }

    /** The template with this id, or the tenant's first one. */
    public synchronized Template getTemplate(String t, Long id) {
        Template found = id == null ? null : findTemplate(t, id);
        return found != null ? found
                : db.sql("SELECT * FROM templates WHERE tenant_id = ? ORDER BY sort, id LIMIT 1").param(t).query(TEMPLATE).optional().orElse(null);
    }

    public synchronized Template createTemplate(String t, String name, String system, String expand, String combine) {
        Integer max = db.sql("SELECT MAX(sort) FROM templates WHERE tenant_id = ?").param(t).query(Integer.class).optional().orElse(null);
        long id = db.sql("INSERT INTO templates (tenant_id, name, system, expand, combine, sort) VALUES (?, ?, ?, ?, ?, ?) RETURNING id")
                .params(t, name, system, expand, combine, (max == null ? 0 : max) + 1).query(Long.class).single();
        return getTemplate(t, id);
    }

    /** null when the tenant has no such template. */
    public synchronized Template updateTemplate(String t, long id, String name, String system, String expand, String combine) {
        int n = db.sql("UPDATE templates SET name = ?, system = ?, expand = ?, combine = ? WHERE id = ? AND tenant_id = ?")
                .params(name, system, expand, combine, id, t).update();
        return n == 0 ? null : findTemplate(t, id);
    }

    public synchronized boolean deleteTemplate(String t, long id) {
        if (count("SELECT COUNT(*) FROM templates WHERE tenant_id = ?", t) <= 1) return false;
        return db.sql("DELETE FROM templates WHERE id = ? AND tenant_id = ?").params(id, t).update() > 0;
    }

    // ---------------------------------------------------------------- report

    /**
     * Everything the report window shows for the last `days` days: totals,
     * every Claude Code project, and per board its goal, summary, best ideas.
     */
    public synchronized Report report(String t, int days) {
        List<Board> boards = boards(t);
        List<Idea> ideas = db.sql(IDEA_SELECT + " WHERE tb.tenant_id = ? ORDER BY i.id").param(t).query(IDEA).list();
        Set<Long> recentIds = new LinkedHashSet<>(db.sql("SELECT id FROM ideas WHERE created_at >= utc_text(?) AND " + OWN)
                .params(days, t).query(Long.class).list());
        Map<Long, String> titles = new LinkedHashMap<>();
        for (Board b : boards) titles.put(b.id(), b.title());
        Comparator<Idea> rank = (x, y) -> {
            int c = Integer.compare(sumOr(y), sumOr(x));
            return c != 0 ? c : Long.compare(y.id(), x.id());
        };
        List<ProjectRef> projects = ideas.stream().filter(i -> i.projectPath() != null && !i.projectPath().isEmpty())
                .map(i -> new ProjectRef(i.id(), i.boardId(), i.title(), i.pitch(), sum(i), medalOf(i), i.reason(), i.kind(),
                        titles.getOrDefault(i.boardId(), ""), i.projectPath()))
                .toList();
        int gold = 0, silver = 0, bronze = 0;
        for (Idea i : ideas) {
            Integer m = medalOf(i);
            if (m == null) continue;
            if (m == 3) gold++;
            else if (m == 2) silver++;
            else if (m == 1) bronze++;
        }
        Double spent = db.sql("SELECT SUM(usd) AS usd FROM spend WHERE tenant_id = ? AND day >= ?")
                .params(t, localDay(LocalDate.now().minusDays(days - 1))).query(Double.class).optional().orElse(null);
        ObjectNode synthesis = db.sql("SELECT * FROM reports WHERE tenant_id = ? ORDER BY id DESC LIMIT 1").param(t).query((r, n) -> {
            JsonNode c = parseJson(r.getString("content"));
            ObjectNode o = c instanceof ObjectNode on ? on : Js.object();
            o.put("days", r.getInt("days"));
            o.put("lang", r.getString("lang"));
            o.put("createdAt", r.getString("created_at"));
            return o;
        }).optional().orElse(null);
        List<ReportBoard> perBoard = boards.stream().map(b -> {
            List<Idea> own = ideas.stream().filter(i -> i.boardId() == b.id()).toList();
            ObjectNode summary = null;
            if (b.summary() != null) {
                summary = b.summary().deepCopy();
                summary.put("stale", b.summary().path("ideaCount").asDouble(-1) != own.size());
            }
            return new ReportBoard(b.id(), b.title(), b.goal(), b.config().mode(), own.size(),
                    (int) own.stream().filter(i -> recentIds.contains(i.id())).count(),
                    own.stream().filter(i -> sum(i) != null || i.medalOverride() != null).sorted(rank).limit(3).map(Repo::brief).toList(),
                    summary, projects.stream().filter(p -> p.boardId() == b.id()).toList());
        }).toList();
        return new Report(days,
                new Totals(boards.size(), ideas.size(), recentIds.size(), (int) ideas.stream().filter(i -> i.judgedAt() != null).count(),
                        new Medals(gold, silver, bronze), projects.size(), Math.round((spent == null ? 0 : spent) * 10000) / 10000.0),
                projects, perBoard, synthesis);
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

    public synchronized void saveSynthesis(String t, int days, String lang, ObjectNode content) {
        db.sql("INSERT INTO reports (tenant_id, days, lang, content) VALUES (?, ?, ?, ?)").params(t, days, lang, Js.stringify(content)).update();
    }

    // ---------------------------------------------------------------- spend ledger (per tenant, board, local day)

    /** YYYY-MM-DD in local time: budgets reset at local midnight. */
    public static String localDay(LocalDate d) {
        return d.toString();
    }

    public static String localDay() {
        return LocalDate.now().toString();
    }

    public synchronized void addSpend(String t, long boardId, double usd) {
        db.sql("""
                        INSERT INTO spend (tenant_id, board_id, day, usd, calls) VALUES (?, ?, ?, ?, 1)
                         ON CONFLICT (tenant_id, board_id, day) DO UPDATE SET usd = spend.usd + excluded.usd, calls = spend.calls + 1""")
                .params(t, boardId, localDay(), usd > 0 ? usd : 0).update();
    }

    /** Spent today on one board, or (boardId null) across all the tenant's boards. */
    public synchronized double spentOn(String t, Long boardId) {
        Double v = boardId == null
                ? db.sql("SELECT SUM(usd) FROM spend WHERE tenant_id = ? AND day = ?").params(t, localDay()).query(Double.class).optional().orElse(null)
                : db.sql("SELECT usd FROM spend WHERE tenant_id = ? AND board_id = ? AND day = ?").params(t, boardId, localDay())
                        .query(Double.class).optional().orElse(null);
        return v == null ? 0 : v;
    }

    public synchronized Spent spentTotal(String t) {
        return db.sql("SELECT SUM(usd) AS usd, SUM(calls) AS calls FROM spend WHERE tenant_id = ?").param(t)
                .query((r, n) -> new Spent(r.getDouble("usd"), r.getLong("calls"))).single();
    }
}
