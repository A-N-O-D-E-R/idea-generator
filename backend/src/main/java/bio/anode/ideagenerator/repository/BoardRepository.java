package bio.anode.ideagenerator.repository;

import bio.anode.ideagenerator.AppError;
import bio.anode.ideagenerator.Js;
import bio.anode.ideagenerator.model.Board;
import bio.anode.ideagenerator.model.Board.Spinoff;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Boards. Every query is scoped by tenant: another tenant's board is simply not found. */
@Repository
@RequiredArgsConstructor
public class BoardRepository {
    static final String BOARD_SELECT = """
            SELECT b.*, oi.title AS origin_idea_title, oi.board_id AS origin_board_id, ob.title AS origin_board_title
              FROM boards b LEFT JOIN ideas oi ON oi.id = b.origin_idea_id LEFT JOIN boards ob ON ob.id = oi.board_id""";

    static final RowMapper<Board> BOARD = (r, n) -> {
        String summary = r.getString("summary");
        JsonNode s = summary == null || summary.isEmpty() ? null : Rows.json(summary);
        return new Board(r.getLong("id"), r.getString("title"), r.getString("goal"),
                Board.mergeConfig(Board.defaultConfig(), Rows.json(r.getString("config"))),
                r.getString("created_at"), r.getString("updated_at"), Rows.longOrNull(r, "origin_idea_id"),
                r.getString("origin_idea_title"), Rows.longOrNull(r, "origin_board_id"), r.getString("origin_board_title"),
                s instanceof ObjectNode o ? o : s == null ? null : Js.object(), r.getString("tenant_id"));
    };

    private final JdbcClient db;

    /** Boards, newest activity first. */
    public List<Board> list(String t) {
        return db.sql("SELECT * FROM (" + BOARD_SELECT + " WHERE b.tenant_id = ?) b2 ORDER BY b2.updated_at DESC, b2.id DESC")
                .param(t).query(BOARD).list();
    }

    /** Every tenant's boards in automatic mode, for the autopilot (the one cross-tenant read). */
    public List<Board> auto() {
        return db.sql(BOARD_SELECT + " ORDER BY b.id").query(BOARD).list().stream().filter(b -> "auto".equals(b.config().mode())).toList();
    }

    public Board find(String t, long id) {
        return db.sql(BOARD_SELECT + " WHERE b.id = ? AND b.tenant_id = ?").params(id, t).query(BOARD).optional().orElse(null);
    }

    public Board require(String t, long id) {
        Board b = find(t, id);
        if (b == null) throw new AppError(404, "not_found", "Board not found");
        return b;
    }

    public synchronized Board insert(String t, String title, String goal, Board.Config config, Long originIdeaId) {
        long id = db.sql("INSERT INTO boards (tenant_id, title, goal, config, origin_idea_id) VALUES (?, ?, ?, ?, ?) RETURNING id")
                .params(t, title, goal == null ? "" : goal, Js.stringify(config), originIdeaId).query(Long.class).single();
        return find(t, id);
    }

    /** title/goal null = unchanged; config null = unchanged, otherwise merged onto the current one. */
    public synchronized Board update(String t, long id, String title, String goal, JsonNode config) {
        Board cur = find(t, id);
        if (cur == null) return null;
        Board.Config c = config == null ? cur.config() : Board.mergeConfig(cur.config(), config);
        db.sql("UPDATE boards SET title = ?, goal = ?, config = ?, updated_at = utc_text() WHERE id = ? AND tenant_id = ?")
                .params(title != null ? title : cur.title(), goal != null ? goal : cur.goal(), Js.stringify(c), id, t).update();
        return find(t, id);
    }

    public Board setSummary(String t, long id, ObjectNode summary) {
        db.sql("UPDATE boards SET summary = ? WHERE id = ? AND tenant_id = ?").params(Js.stringify(summary), id, t).update();
        return find(t, id);
    }

    public void touch(String t, long id) {
        db.sql("UPDATE boards SET updated_at = utc_text() WHERE id = ? AND tenant_id = ?").params(id, t).update();
    }

    /** Boards spun off from ideas on this board. */
    public List<Spinoff> spinoffs(String t, long boardId) {
        return db.sql("""
                        SELECT b.id AS board_id, b.title, b.origin_idea_id FROM boards b JOIN ideas i ON i.id = b.origin_idea_id
                         WHERE i.board_id = ? AND b.tenant_id = ? ORDER BY b.id""")
                .params(boardId, t)
                .query((r, n) -> new Spinoff(r.getLong("origin_idea_id"), r.getLong("board_id"), r.getString("title"))).list();
    }

    public boolean delete(String t, long id) {
        return db.sql("DELETE FROM boards WHERE id = ? AND tenant_id = ?").params(id, t).update() > 0;
    }
}
