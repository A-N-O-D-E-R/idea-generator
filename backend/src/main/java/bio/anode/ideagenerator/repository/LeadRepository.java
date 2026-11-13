package bio.anode.ideagenerator.repository;

import bio.anode.ideagenerator.Js;
import bio.anode.ideagenerator.model.Lead;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** The scouts' leads of a board. */
@Repository
@RequiredArgsConstructor
public class LeadRepository {
    private static final RowMapper<Lead> LEAD = (r, n) -> new Lead(r.getLong("id"), r.getLong("board_id"), r.getString("source"),
            r.getString("title"), r.getString("summary"), r.getString("hook"), r.getString("url"), Rows.json(r.getString("meta")),
            Rows.longOrNull(r, "idea_id"), r.getString("fetched_at"));

    private final JdbcClient db;
    private final TransactionTemplate tx;

    public List<Lead> list(String t, long boardId) {
        return db.sql("SELECT * FROM leads WHERE board_id = ? AND " + Rows.OWN + " ORDER BY source, id").params(boardId, t).query(LEAD).list();
    }

    public Lead find(String t, long id) {
        return db.sql("SELECT * FROM leads WHERE id = ? AND " + Rows.OWN).params(id, t).query(LEAD).optional().orElse(null);
    }

    /** A refresh replaces the unused leads of that source; used ones stay as history. */
    public List<Lead> replace(String t, long boardId, String source, List<Lead.Draft> drafts) {
        if (Rows.count(db, "SELECT COUNT(*) FROM boards WHERE id = ? AND tenant_id = ?", boardId, t) == 0) return List.of();
        tx.executeWithoutResult(s -> {
            db.sql("DELETE FROM leads WHERE board_id = ? AND source = ? AND idea_id IS NULL").params(boardId, source).update();
            for (Lead.Draft d : drafts) {
                db.sql("INSERT INTO leads (board_id, source, title, summary, hook, url, meta) VALUES (?, ?, ?, ?, ?, ?, ?)")
                        .params(boardId, source, d.title(), d.summary(), d.hook(), d.url(),
                                Js.stringify(d.meta() == null ? Map.of() : d.meta())).update();
            }
        });
        return list(t, boardId).stream().filter(l -> l.source().equals(source)).toList();
    }

    public Lead markUsed(String t, long id, long ideaId) {
        db.sql("UPDATE leads SET idea_id = ? WHERE id = ? AND " + Rows.OWN).params(ideaId, id, t).update();
        return find(t, id);
    }

    public boolean delete(String t, long id) {
        return db.sql("DELETE FROM leads WHERE id = ? AND " + Rows.OWN).params(id, t).update() > 0;
    }
}
