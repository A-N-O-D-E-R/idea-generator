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
 * SQLite, ported from upstream db.ts: same tables, same JSON-in-column choices,
 * same queries. Every method is synchronized: upstream runs on one synchronous
 * connection, and SQLite wants one writer anyway.
 */
public class Repo {

    static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS templates (
              id INTEGER PRIMARY KEY,
              name TEXT NOT NULL,
              system TEXT NOT NULL,
              expand TEXT NOT NULL,
              combine TEXT NOT NULL,
              sort INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS boards (
              id INTEGER PRIMARY KEY,
              title TEXT NOT NULL,
              goal TEXT NOT NULL DEFAULT '',
              config TEXT NOT NULL DEFAULT '{}',
              origin_idea_id INTEGER REFERENCES ideas(id) ON DELETE SET NULL,
              summary TEXT,
              created_at TEXT NOT NULL DEFAULT (datetime('now')),
              updated_at TEXT NOT NULL DEFAULT (datetime('now'))
            );
            CREATE TABLE IF NOT EXISTS ideas (
              id INTEGER PRIMARY KEY,
              board_id INTEGER NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
              title TEXT NOT NULL,
              pitch TEXT NOT NULL DEFAULT '',
              kind TEXT NOT NULL CHECK (kind IN ('seed', 'expand', 'combine', 'guest')),
              source_url TEXT,
              x REAL NOT NULL DEFAULT 0,
              y REAL NOT NULL DEFAULT 0,
              originality INTEGER,
              feasibility INTEGER,
              impact INTEGER,
              reason TEXT,
              judged_at TEXT,
              medal_override INTEGER,
              generation INTEGER NOT NULL DEFAULT 0,
              project_path TEXT,
              origin_idea_id INTEGER REFERENCES ideas(id) ON DELETE SET NULL,
              origin_board_id INTEGER REFERENCES boards(id) ON DELETE SET NULL,
              created_at TEXT NOT NULL DEFAULT (datetime('now'))
            );
            CREATE INDEX IF NOT EXISTS ideas_board ON ideas(board_id);
            CREATE UNIQUE INDEX IF NOT EXISTS ideas_guest_once ON ideas(board_id, origin_idea_id) WHERE origin_idea_id IS NOT NULL;
            CREATE TABLE IF NOT EXISTS edges (
              board_id INTEGER NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
              from_id INTEGER NOT NULL REFERENCES ideas(id) ON DELETE CASCADE,
              to_id INTEGER NOT NULL REFERENCES ideas(id) ON DELETE CASCADE,
              PRIMARY KEY (from_id, to_id)
            );
            CREATE TABLE IF NOT EXISTS leads (
              id INTEGER PRIMARY KEY,
              board_id INTEGER NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
              source TEXT NOT NULL CHECK (source IN ('x', 'research')),
              title TEXT NOT NULL,
              summary TEXT NOT NULL DEFAULT '',
              hook TEXT NOT NULL DEFAULT '',
              url TEXT,
              meta TEXT NOT NULL DEFAULT '{}',
              idea_id INTEGER REFERENCES ideas(id) ON DELETE SET NULL,
              fetched_at TEXT NOT NULL DEFAULT (datetime('now'))
            );
            CREATE INDEX IF NOT EXISTS leads_board ON leads(board_id);
            -- Keywords come from the judge, in one canonical form, so trends can be counted across boards.
            CREATE TABLE IF NOT EXISTS idea_keywords (
              idea_id INTEGER NOT NULL REFERENCES ideas(id) ON DELETE CASCADE,
              keyword TEXT NOT NULL,
              PRIMARY KEY (idea_id, keyword)
            );
            CREATE INDEX IF NOT EXISTS idea_keywords_kw ON idea_keywords(keyword);
            -- Saved cross-board syntheses for the report window (latest one is shown).
            CREATE TABLE IF NOT EXISTS reports (
              id INTEGER PRIMARY KEY,
              days INTEGER NOT NULL,
              lang TEXT NOT NULL,
              content TEXT NOT NULL,
              created_at TEXT NOT NULL DEFAULT (datetime('now'))
            );
            -- Spend ledger per board and local calendar day; the rate limiter reads it.
            -- board_id 0 = spend that belongs to no board.
            CREATE TABLE IF NOT EXISTS spend (
              board_id INTEGER NOT NULL,
              day TEXT NOT NULL,
              usd REAL NOT NULL DEFAULT 0,
              calls INTEGER NOT NULL DEFAULT 0,
              PRIMARY KEY (board_id, day)
            );
            """;

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
        db.sql("PRAGMA foreign_keys = ON").update();
        db.sql("PRAGMA journal_mode = WAL").query().singleValue(); // in-memory databases answer "memory"
        for (String stmt : SCHEMA.split(";\\s*\\n")) if (!stmt.isBlank()) db.sql(stmt).update();
        if (count("SELECT COUNT(*) FROM templates") == 0) {
            for (Template t : Board.DEFAULT_TEMPLATES) {
                db.sql("INSERT INTO templates (name, system, expand, combine, sort) VALUES (?, ?, ?, ?, ?)")
                        .params(t.name(), t.system(), t.expand(), t.combine(), t.sort()).update();
            }
        }
    }

    private long count(String sql, Object... params) {
        return db.sql(sql).params(params).query(Long.class).single();
    }

    private <T> T inTx(Supplier<T> fn) {
        return tx.execute(s -> fn.get());
    }

    // ---------------------------------------------------------------- row mapping

    static final String IDEA_SELECT = """
            SELECT i.*, ob.title AS origin_board_title,
              (SELECT group_concat(keyword, '|') FROM (SELECT keyword FROM idea_keywords k WHERE k.idea_id = i.id ORDER BY keyword)) AS keywords
              FROM ideas i LEFT JOIN boards ob ON ob.id = i.origin_board_id""";

    static final String BOARD_SELECT = """
            SELECT b.*, oi.title AS origin_idea_title, oi.board_id AS origin_board_id, ob.title AS origin_board_title
              FROM boards b LEFT JOIN ideas oi ON oi.id = b.origin_idea_id LEFT JOIN boards ob ON ob.id = oi.board_id""";

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
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
                kw == null || kw.isEmpty() ? List.of() : Arrays.asList(kw.split("\\|")), r.getString("created_at"));
    };

    static final RowMapper<Board> BOARD = (r, n) -> {
        String summary = r.getString("summary");
        JsonNode s = summary == null || summary.isEmpty() ? null : parseJson(summary);
        return new Board(r.getLong("id"), r.getString("title"), r.getString("goal"),
                Board.mergeConfig(Board.defaultConfig(), parseJson(r.getString("config"))),
                r.getString("created_at"), r.getString("updated_at"), longOrNull(r, "origin_idea_id"),
                r.getString("origin_idea_title"), longOrNull(r, "origin_board_id"), r.getString("origin_board_title"),
                s instanceof ObjectNode o ? o : s == null ? null : Js.object());
    };

    static final RowMapper<Lead> LEAD = (r, n) -> new Lead(r.getLong("id"), r.getLong("board_id"), r.getString("source"),
            r.getString("title"), r.getString("summary"), r.getString("hook"), r.getString("url"), parseJson(r.getString("meta")),
            longOrNull(r, "idea_id"), r.getString("fetched_at"));

    static final RowMapper<Template> TEMPLATE = (r, n) -> new Template(r.getLong("id"), r.getString("name"),
            r.getString("system"), r.getString("expand"), r.getString("combine"), r.getInt("sort"));

    // ---------------------------------------------------------------- boards

    /** Boards, newest activity first, each with its ideaCount. */
    public synchronized List<ObjectNode> listBoards() {
        return boards().stream().map(b -> {
            ObjectNode n = Js.tree(b);
            n.put("ideaCount", count("SELECT COUNT(*) FROM ideas WHERE board_id = ?", b.id()));
            return n;
        }).toList();
    }

    private List<Board> boards() {
        return db.sql("SELECT * FROM (" + BOARD_SELECT + ") b2 ORDER BY b2.updated_at DESC, b2.id DESC").query(BOARD).list();
    }

    public synchronized Board getBoard(long id) {
        return db.sql(BOARD_SELECT + " WHERE b.id = ?").param(id).query(BOARD).optional().orElse(null);
    }

    public synchronized Board createBoard(String title, String goal, Long templateId, JsonNode config, Long originIdeaId) {
        Board.Config c = Board.mergeConfig(Board.defaultConfig(getTemplate(templateId)), config);
        long id = db.sql("INSERT INTO boards (title, goal, config, origin_idea_id) VALUES (?, ?, ?, ?) RETURNING id")
                .params(title, goal == null ? "" : goal, Js.stringify(c), originIdeaId).query(Long.class).single();
        return getBoard(id);
    }

    public Board createBoard(String title, JsonNode config) {
        return createBoard(title, "", null, config, null);
    }

    /** title/goal null = unchanged; config null = unchanged, otherwise merged onto the current one. */
    public synchronized Board updateBoard(long id, String title, String goal, JsonNode config) {
        Board cur = getBoard(id);
        if (cur == null) return null;
        Board.Config c = config == null ? cur.config() : Board.mergeConfig(cur.config(), config);
        db.sql("UPDATE boards SET title = ?, goal = ?, config = ?, updated_at = datetime('now') WHERE id = ?")
                .params(title != null ? title : cur.title(), goal != null ? goal : cur.goal(), Js.stringify(c), id).update();
        return getBoard(id);
    }

    public synchronized Board setSummary(long id, ObjectNode summary) {
        db.sql("UPDATE boards SET summary = ? WHERE id = ?").params(Js.stringify(summary), id).update();
        return getBoard(id);
    }

    public synchronized void touchBoard(long id) {
        db.sql("UPDATE boards SET updated_at = datetime('now') WHERE id = ?").param(id).update();
    }

    /** Boards spun off from ideas on this board. */
    public synchronized List<Spinoff> spinoffs(long boardId) {
        return db.sql("SELECT b.id AS board_id, b.title, b.origin_idea_id FROM boards b JOIN ideas i ON i.id = b.origin_idea_id WHERE i.board_id = ? ORDER BY b.id")
                .param(boardId)
                .query((r, n) -> new Spinoff(r.getLong("origin_idea_id"), r.getLong("board_id"), r.getString("title"))).list();
    }

    public synchronized boolean deleteBoard(long id) {
        return db.sql("DELETE FROM boards WHERE id = ?").param(id).update() > 0;
    }

    // ---------------------------------------------------------------- ideas

    public synchronized List<Idea> listIdeas(long boardId) {
        return db.sql(IDEA_SELECT + " WHERE i.board_id = ? ORDER BY i.id").param(boardId).query(IDEA).list();
    }

    public synchronized Idea getIdea(long id) {
        return db.sql(IDEA_SELECT + " WHERE i.id = ?").param(id).query(IDEA).optional().orElse(null);
    }

    public record NewIdea(long boardId, String title, String pitch, String kind, String sourceUrl, double x, double y,
                          int generation, Long originIdeaId, Long originBoardId) {
        public NewIdea(long boardId, String title, String pitch, String kind, String sourceUrl, double x, double y, int generation) {
            this(boardId, title, pitch, kind, sourceUrl, x, y, generation, null, null);
        }
    }

    /** Insert an idea and the edges from its parents in one transaction. */
    public synchronized Idea insertIdea(NewIdea i, List<Long> parentIds) {
        return inTx(() -> {
            long id = db.sql("""
                            INSERT INTO ideas (board_id, title, pitch, kind, source_url, x, y, generation, origin_idea_id, origin_board_id)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                    .params(i.boardId(), i.title(), i.pitch(), i.kind(), i.sourceUrl(), i.x(), i.y(), i.generation(),
                            i.originIdeaId(), i.originBoardId())
                    .query(Long.class).single();
            for (long p : parentIds) {
                db.sql("INSERT OR IGNORE INTO edges (board_id, from_id, to_id) VALUES (?, ?, ?)").params(i.boardId(), p, id).update();
            }
            touchBoard(i.boardId());
            return getIdea(id);
        });
    }

    public Idea insertIdea(NewIdea i) {
        return insertIdea(i, List.of());
    }

    /** Is this original already a guest on that board? */
    public synchronized boolean hasGuest(long boardId, long originIdeaId) {
        return count("SELECT COUNT(*) FROM ideas WHERE board_id = ? AND (origin_idea_id = ? OR id = ?)", boardId, originIdeaId, originIdeaId) > 0;
    }

    public synchronized Idea saveJudgement(long id, Llm.Judgement j) {
        db.sql("UPDATE ideas SET originality = ?, feasibility = ?, impact = ?, reason = ?, judged_at = datetime('now') WHERE id = ?")
                .params(j.originality(), j.feasibility(), j.impact(), j.reason(), id).update();
        return getIdea(id);
    }

    public synchronized Idea setKeywords(long id, List<String> keywords) {
        inTx(() -> {
            db.sql("DELETE FROM idea_keywords WHERE idea_id = ?").param(id).update();
            for (String k : keywords) db.sql("INSERT OR IGNORE INTO idea_keywords (idea_id, keyword) VALUES (?, ?)").params(id, k).update();
            return null;
        });
        return getIdea(id);
    }

    /** The keywords in use, most common first, so the judge can reuse them. */
    public synchronized List<String> keywordVocabulary() {
        return db.sql("SELECT keyword, COUNT(*) AS n FROM idea_keywords GROUP BY keyword ORDER BY n DESC, keyword LIMIT ?")
                .param(80).query((r, n) -> r.getString("keyword")).list();
    }

    /**
     * Keyword trends across all boards. Per keyword: how many ideas, on how many
     * boards, mean jury sum, ideas in the last `days` vs the `days` before, and
     * a per-day count series for the last `days`.
     */
    public synchronized Dashboard dashboard(int days) {
        String since = "-" + days + " days";
        String before = "-" + (days * 2) + " days";
        List<Map<String, Object>> rows = db.sql("""
                        SELECT k.keyword,
                           COUNT(*) AS count,
                           COUNT(DISTINCT i.board_id) AS boards,
                           AVG(i.originality + i.feasibility + i.impact) AS mean_sum,
                           SUM(i.created_at >= datetime('now', ?)) AS recent,
                           SUM(i.created_at < datetime('now', ?) AND i.created_at >= datetime('now', ?)) AS previous
                         FROM idea_keywords k JOIN ideas i ON i.id = k.idea_id
                         GROUP BY k.keyword
                         ORDER BY recent DESC, count DESC, k.keyword
                         LIMIT ?""")
                .params(since, since, before, 40).query().listOfRows();
        List<Map<String, Object>> seriesRows = db.sql("""
                        SELECT k.keyword, substr(i.created_at, 1, 10) AS day, COUNT(*) AS n
                         FROM idea_keywords k JOIN ideas i ON i.id = k.idea_id
                         WHERE i.created_at >= datetime('now', ?)
                         GROUP BY k.keyword, day""")
                .param(since).query().listOfRows();
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
                         GROUP BY b.id, k.keyword ORDER BY b.id, n DESC, k.keyword""")
                .query(rs -> {
                    BoardKeywords pb = perBoard.computeIfAbsent(rs.getLong("id"),
                            id -> new BoardKeywords(id, uncheckedString(rs, "title"), new ArrayList<>()));
                    if (pb.keywords().size() < 6) pb.keywords().add(new KeywordCount(rs.getString("keyword"), rs.getLong("n")));
                });
        DashboardTotals totals = db.sql("""
                        SELECT (SELECT COUNT(*) FROM ideas) AS ideas, (SELECT COUNT(*) FROM boards) AS boards,
                                (SELECT COUNT(DISTINCT keyword) FROM idea_keywords) AS keywords,
                                (SELECT COUNT(*) FROM ideas WHERE created_at >= datetime('now', ?)) AS recent""")
                .param(since)
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
    public synchronized List<ObjectNode> ideasWithKeyword(String keyword) {
        return db.sql("""
                        SELECT i.*, b.title AS board_title, NULL AS origin_board_title, NULL AS keywords
                         FROM idea_keywords k JOIN ideas i ON i.id = k.idea_id JOIN boards b ON b.id = i.board_id
                         WHERE k.keyword = ? ORDER BY i.id DESC LIMIT ?""")
                .params(keyword, 100)
                .query((r, n) -> {
                    ObjectNode o = Js.tree(IDEA.mapRow(r, n));
                    o.put("boardTitle", r.getString("board_title"));
                    return o;
                }).list();
    }

    public synchronized Idea setMedalOverride(long id, Integer medal) {
        db.sql("UPDATE ideas SET medal_override = ? WHERE id = ?").params(medal, id).update();
        return getIdea(id);
    }

    public synchronized Idea setProjectPath(long id, String path) {
        db.sql("UPDATE ideas SET project_path = ? WHERE id = ?").params(path, id).update();
        return getIdea(id);
    }

    public record Position(long id, double x, double y) {}

    public synchronized void savePositions(long boardId, List<Position> positions) {
        inTx(() -> {
            for (Position p : positions) {
                db.sql("UPDATE ideas SET x = ?, y = ? WHERE id = ? AND board_id = ?").params(p.x(), p.y(), p.id(), boardId).update();
            }
            return null;
        });
    }

    public synchronized boolean deleteIdea(long id) {
        return db.sql("DELETE FROM ideas WHERE id = ?").param(id).update() > 0;
    }

    // ---------------------------------------------------------------- edges and lineage

    public synchronized List<Edge> listEdges(long boardId) {
        return db.sql("SELECT from_id, to_id FROM edges WHERE board_id = ? ORDER BY rowid").param(boardId)
                .query((r, n) -> new Edge(r.getLong("from_id"), r.getLong("to_id"))).list();
    }

    public synchronized List<Idea> parentsOf(long id) {
        return db.sql(IDEA_SELECT + " JOIN edges e ON i.id = e.from_id WHERE e.to_id = ? ORDER BY i.id").param(id).query(IDEA).list();
    }

    /** Ancestors, nearest first. A guest continues into its original's ancestry on the other board. */
    public synchronized List<Idea> lineage(long id) {
        int max = 12;
        List<Idea> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>(Set.of(id));
        java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>(List.of(id));
        while (!queue.isEmpty() && out.size() < max) {
            Idea cur = getIdea(queue.poll());
            if (cur == null) continue;
            List<Idea> next = new ArrayList<>(parentsOf(cur.id()));
            if (cur.originIdeaId() != null) {
                Idea origin = getIdea(cur.originIdeaId());
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

    public synchronized List<Lead> listLeads(long boardId) {
        return db.sql("SELECT * FROM leads WHERE board_id = ? ORDER BY source, id").param(boardId).query(LEAD).list();
    }

    public synchronized Lead getLead(long id) {
        return db.sql("SELECT * FROM leads WHERE id = ?").param(id).query(LEAD).optional().orElse(null);
    }

    /** A refresh replaces the unused leads of that source; used ones stay as history. */
    public synchronized List<Lead> replaceLeads(long boardId, String source, List<LeadDraft> drafts) {
        inTx(() -> {
            db.sql("DELETE FROM leads WHERE board_id = ? AND source = ? AND idea_id IS NULL").params(boardId, source).update();
            for (LeadDraft d : drafts) {
                db.sql("INSERT INTO leads (board_id, source, title, summary, hook, url, meta) VALUES (?, ?, ?, ?, ?, ?, ?)")
                        .params(boardId, source, d.title(), d.summary(), d.hook(), d.url(),
                                Js.stringify(d.meta() == null ? Map.of() : d.meta())).update();
            }
            return null;
        });
        return listLeads(boardId).stream().filter(l -> l.source().equals(source)).toList();
    }

    public synchronized Lead markLeadUsed(long id, long ideaId) {
        db.sql("UPDATE leads SET idea_id = ? WHERE id = ?").params(ideaId, id).update();
        return getLead(id);
    }

    public synchronized boolean deleteLead(long id) {
        return db.sql("DELETE FROM leads WHERE id = ?").param(id).update() > 0;
    }

    // ---------------------------------------------------------------- templates

    public synchronized List<Template> listTemplates() {
        return db.sql("SELECT * FROM templates ORDER BY sort, id").query(TEMPLATE).list();
    }

    /** The template with this id, or the first one. */
    public synchronized Template getTemplate(Long id) {
        Template t = id == null ? null : db.sql("SELECT * FROM templates WHERE id = ?").param(id).query(TEMPLATE).optional().orElse(null);
        return t != null ? t : db.sql("SELECT * FROM templates ORDER BY sort, id LIMIT 1").query(TEMPLATE).optional().orElse(null);
    }

    public synchronized Template createTemplate(String name, String system, String expand, String combine) {
        Integer max = db.sql("SELECT MAX(sort) FROM templates").query(Integer.class).optional().orElse(null);
        long id = db.sql("INSERT INTO templates (name, system, expand, combine, sort) VALUES (?, ?, ?, ?, ?) RETURNING id")
                .params(name, system, expand, combine, (max == null ? 0 : max) + 1).query(Long.class).single();
        return getTemplate(id);
    }

    public synchronized Template updateTemplate(long id, String name, String system, String expand, String combine) {
        db.sql("UPDATE templates SET name = ?, system = ?, expand = ?, combine = ? WHERE id = ?")
                .params(name, system, expand, combine, id).update();
        return getTemplate(id);
    }

    public synchronized boolean deleteTemplate(long id) {
        if (count("SELECT COUNT(*) FROM templates") <= 1) return false;
        return db.sql("DELETE FROM templates WHERE id = ?").param(id).update() > 0;
    }

    // ---------------------------------------------------------------- report

    /**
     * Everything the report window shows for the last `days` days: totals,
     * every Claude Code project, and per board its goal, summary, best ideas.
     */
    public synchronized Report report(int days) {
        String since = "-" + days + " days";
        List<Board> boards = boards();
        List<Idea> ideas = db.sql(IDEA_SELECT + " ORDER BY i.id").query(IDEA).list();
        Set<Long> recentIds = new LinkedHashSet<>(db.sql("SELECT id FROM ideas WHERE created_at >= datetime('now', ?)")
                .param(since).query(Long.class).list());
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
        Double spent = db.sql("SELECT SUM(usd) AS usd FROM spend WHERE day >= ?").param(localDay(LocalDate.now().minusDays(days - 1)))
                .query(Double.class).optional().orElse(null);
        ObjectNode synthesis = db.sql("SELECT * FROM reports ORDER BY id DESC LIMIT 1").query((r, n) -> {
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

    public synchronized void saveSynthesis(int days, String lang, ObjectNode content) {
        db.sql("INSERT INTO reports (days, lang, content) VALUES (?, ?, ?)").params(days, lang, Js.stringify(content)).update();
    }

    // ---------------------------------------------------------------- spend ledger (per board, per local day)

    /** YYYY-MM-DD in local time: budgets reset at local midnight. */
    public static String localDay(LocalDate d) {
        return d.toString();
    }

    public static String localDay() {
        return LocalDate.now().toString();
    }

    public synchronized void addSpend(long boardId, double usd) {
        db.sql("""
                        INSERT INTO spend (board_id, day, usd, calls) VALUES (?, ?, ?, 1)
                         ON CONFLICT(board_id, day) DO UPDATE SET usd = usd + excluded.usd, calls = calls + 1""")
                .params(boardId, localDay(), usd > 0 ? usd : 0).update();
    }

    /** Spent today on one board, or (boardId null) across all boards. */
    public synchronized double spentOn(Long boardId) {
        Double v = boardId == null
                ? db.sql("SELECT SUM(usd) FROM spend WHERE day = ?").param(localDay()).query(Double.class).optional().orElse(null)
                : db.sql("SELECT usd FROM spend WHERE board_id = ? AND day = ?").params(boardId, localDay()).query(Double.class).optional().orElse(null);
        return v == null ? 0 : v;
    }

    public synchronized Spent spentTotal() {
        return db.sql("SELECT SUM(usd) AS usd, SUM(calls) AS calls FROM spend")
                .query((r, n) -> new Spent(r.getDouble("usd"), r.getLong("calls"))).single();
    }
}
