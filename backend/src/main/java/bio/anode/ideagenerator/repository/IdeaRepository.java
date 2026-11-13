package bio.anode.ideagenerator.repository;

import bio.anode.ideagenerator.AppError;
import bio.anode.ideagenerator.Js;
import bio.anode.ideagenerator.Llm;
import bio.anode.ideagenerator.model.Idea;
import bio.anode.ideagenerator.model.Idea.Edge;
import bio.anode.ideagenerator.model.Idea.Position;
import bio.anode.ideagenerator.model.NewIdea;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

/**
 * Ideas, their edges and keywords. Ideas hang off boards, so every query checks the board's tenant:
 * a row of another tenant is never read, changed or deleted, it is simply not found.
 * ponytail: synchronized per repository (upstream ran on one synchronous connection); per-tenant locks or none if throughput matters.
 */
@Repository
@RequiredArgsConstructor
public class IdeaRepository {
    static final String IDEA_SELECT = """
            SELECT i.*, ob.title AS origin_board_title,
              (SELECT string_agg(keyword, '|' ORDER BY keyword) FROM idea_keywords k WHERE k.idea_id = i.id) AS keywords
              FROM ideas i JOIN boards tb ON tb.id = i.board_id LEFT JOIN boards ob ON ob.id = i.origin_board_id""";

    /** The tenant's ideas joined to their keywords (for queries that start from idea_keywords k). */
    static final String OWN_IDEAS = "JOIN ideas i ON i.id = k.idea_id JOIN boards b ON b.id = i.board_id AND b.tenant_id = ? ";

    static final RowMapper<Idea> IDEA = (r, n) -> {
        String kw = r.getString("keywords");
        return new Idea(r.getLong("id"), r.getLong("board_id"), r.getString("title"), r.getString("pitch"), r.getString("kind"),
                r.getString("source_url"), r.getDouble("x"), r.getDouble("y"), Rows.intOrNull(r, "originality"),
                Rows.intOrNull(r, "feasibility"), Rows.intOrNull(r, "impact"), r.getString("reason"), r.getString("judged_at"),
                Rows.intOrNull(r, "medal_override"), r.getInt("generation"), r.getString("project_path"),
                Rows.longOrNull(r, "origin_idea_id"), Rows.longOrNull(r, "origin_board_id"), r.getString("origin_board_title"),
                kw == null || kw.isEmpty() ? List.of() : Arrays.asList(kw.split("\\|")), r.getString("created_at"),
                Rows.doubleOrNull(r, "elo"), r.getInt("duels"));
    };

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final BoardRepository boards;

    public synchronized List<Idea> list(String t, long boardId) {
        return db.sql(IDEA_SELECT + " WHERE i.board_id = ? AND tb.tenant_id = ? ORDER BY i.id").params(boardId, t).query(IDEA).list();
    }

    /** Every idea of the tenant. */
    public synchronized List<Idea> listAll(String t) {
        return db.sql(IDEA_SELECT + " WHERE tb.tenant_id = ? ORDER BY i.id").param(t).query(IDEA).list();
    }

    public long count(long boardId) {
        return Rows.count(db, "SELECT COUNT(*) FROM ideas WHERE board_id = ?", boardId);
    }

    public synchronized Idea find(String t, long id) {
        return db.sql(IDEA_SELECT + " WHERE i.id = ? AND tb.tenant_id = ?").params(id, t).query(IDEA).optional().orElse(null);
    }

    public Idea require(String t, long id) {
        Idea i = find(t, id);
        if (i == null) throw new AppError(404, "not_found", "Idea not found");
        return i;
    }

    /** Insert an idea and the edges from its parents (ideas of the same board) in one transaction. */
    public synchronized Idea insert(String t, NewIdea i, List<Long> parentIds) {
        return tx.execute(s -> {
            if (Rows.count(db, "SELECT COUNT(*) FROM boards WHERE id = ? AND tenant_id = ?", i.boardId(), t) == 0) {
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
            boards.touch(t, i.boardId());
            return find(t, id);
        });
    }

    public Idea insert(String t, NewIdea i) {
        return insert(t, i, List.of());
    }

    /** Is this original already a guest on that board? */
    public boolean hasGuest(String t, long boardId, long originIdeaId) {
        return Rows.count(db, "SELECT COUNT(*) FROM ideas WHERE board_id = ? AND (origin_idea_id = ? OR id = ?) AND " + Rows.OWN,
                boardId, originIdeaId, originIdeaId, t) > 0;
    }

    public synchronized Idea saveJudgement(String t, long id, Llm.Judgement j) {
        db.sql("UPDATE ideas SET originality = ?, feasibility = ?, impact = ?, reason = ?, judged_at = utc_text() WHERE id = ? AND " + Rows.OWN)
                .params(j.originality(), j.feasibility(), j.impact(), j.reason(), id, t).update();
        return find(t, id);
    }

    /** Store both sides of a duel at once. */
    public synchronized void saveDuel(String t, long a, double eloA, long b, double eloB) {
        tx.executeWithoutResult(s -> {
            db.sql("UPDATE ideas SET elo = ?, duels = duels + 1 WHERE id = ? AND " + Rows.OWN).params(eloA, a, t).update();
            db.sql("UPDATE ideas SET elo = ?, duels = duels + 1 WHERE id = ? AND " + Rows.OWN).params(eloB, b, t).update();
        });
    }

    public synchronized Idea setKeywords(String t, long id, List<String> keywords) {
        if (find(t, id) == null) return null;
        tx.executeWithoutResult(s -> {
            db.sql("DELETE FROM idea_keywords WHERE idea_id = ?").param(id).update();
            for (String k : keywords) db.sql("INSERT INTO idea_keywords (idea_id, keyword) VALUES (?, ?) ON CONFLICT DO NOTHING").params(id, k).update();
        });
        return find(t, id);
    }

    /** The tenant's keywords in use, most common first, so the judge can reuse them. */
    public List<String> keywordVocabulary(String t) {
        return db.sql("SELECT keyword, COUNT(*) AS n FROM idea_keywords k " + OWN_IDEAS + " GROUP BY keyword ORDER BY n DESC, keyword LIMIT ?")
                .params(t, 80).query((r, n) -> r.getString("keyword")).list();
    }

    /** As upstream: the plain idea row (no keywords, no originBoardTitle) plus boardTitle. */
    public List<ObjectNode> withKeyword(String t, String keyword) {
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
        db.sql("UPDATE ideas SET medal_override = ? WHERE id = ? AND " + Rows.OWN).params(medal, id, t).update();
        return find(t, id);
    }

    public synchronized Idea setProjectPath(String t, long id, String path) {
        db.sql("UPDATE ideas SET project_path = ? WHERE id = ? AND " + Rows.OWN).params(path, id, t).update();
        return find(t, id);
    }

    public synchronized void savePositions(String t, long boardId, List<Position> positions) {
        tx.executeWithoutResult(s -> {
            for (Position p : positions) {
                db.sql("UPDATE ideas SET x = ?, y = ? WHERE id = ? AND board_id = ? AND " + Rows.OWN).params(p.x(), p.y(), p.id(), boardId, t).update();
            }
        });
    }

    public boolean delete(String t, long id) {
        return db.sql("DELETE FROM ideas WHERE id = ? AND " + Rows.OWN).params(id, t).update() > 0;
    }

    // ---------------------------------------------------------------- edges and lineage

    public List<Edge> edges(String t, long boardId) {
        return db.sql("SELECT from_id, to_id FROM edges WHERE board_id = ? AND " + Rows.OWN + " ORDER BY seq").params(boardId, t)
                .query((r, n) -> new Edge(r.getLong("from_id"), r.getLong("to_id"))).list();
    }

    public List<Idea> parentsOf(String t, long id) {
        return db.sql(IDEA_SELECT + " JOIN edges e ON i.id = e.from_id WHERE e.to_id = ? AND tb.tenant_id = ? ORDER BY i.id")
                .params(id, t).query(IDEA).list();
    }

    /** Ancestors, nearest first. A guest continues into its original's ancestry on the other board. */
    public List<Idea> lineage(String t, long id) {
        int max = 12;
        List<Idea> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>(Set.of(id));
        ArrayDeque<Long> queue = new ArrayDeque<>(List.of(id));
        while (!queue.isEmpty() && out.size() < max) {
            Idea cur = find(t, queue.poll());
            if (cur == null) continue;
            List<Idea> next = new ArrayList<>(parentsOf(t, cur.id()));
            if (cur.originIdeaId() != null) {
                Idea origin = find(t, cur.originIdeaId());
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
}
