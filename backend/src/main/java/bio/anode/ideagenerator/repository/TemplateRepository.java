package bio.anode.ideagenerator.repository;

import bio.anode.ideagenerator.model.Board;
import bio.anode.ideagenerator.model.Board.Template;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** A tenant's flow templates (they only seed a new board's flow). */
@Repository
@RequiredArgsConstructor
public class TemplateRepository {
    private static final RowMapper<Template> TEMPLATE = (r, n) -> new Template(r.getLong("id"), r.getString("name"),
            r.getString("system"), r.getString("expand"), r.getString("combine"), r.getInt("sort"));

    private final JdbcClient db;

    public synchronized List<Template> list(String t) {
        return db.sql("SELECT * FROM templates WHERE tenant_id = ? ORDER BY sort, id").param(t).query(TEMPLATE).list();
    }

    public long count(String t) {
        return Rows.count(db, "SELECT COUNT(*) FROM templates WHERE tenant_id = ?", t);
    }

    /** The tenant's template with this id, or null. */
    public synchronized Template find(String t, long id) {
        return db.sql("SELECT * FROM templates WHERE id = ? AND tenant_id = ?").params(id, t).query(TEMPLATE).optional().orElse(null);
    }

    /** The template with this id, or the tenant's first one. */
    public synchronized Template findOrFirst(String t, Long id) {
        Template found = id == null ? null : find(t, id);
        return found != null ? found
                : db.sql("SELECT * FROM templates WHERE tenant_id = ? ORDER BY sort, id LIMIT 1").param(t).query(TEMPLATE).optional().orElse(null);
    }

    public synchronized void addDefaults(String t) {
        for (Template d : Board.DEFAULT_TEMPLATES) {
            db.sql("INSERT INTO templates (tenant_id, name, system, expand, combine, sort) VALUES (?, ?, ?, ?, ?, ?)")
                    .params(t, d.name(), d.system(), d.expand(), d.combine(), d.sort()).update();
        }
    }

    public synchronized Template create(String t, String name, String system, String expand, String combine) {
        Integer max = db.sql("SELECT MAX(sort) FROM templates WHERE tenant_id = ?").param(t).query(Integer.class).optional().orElse(null);
        long id = db.sql("INSERT INTO templates (tenant_id, name, system, expand, combine, sort) VALUES (?, ?, ?, ?, ?, ?) RETURNING id")
                .params(t, name, system, expand, combine, (max == null ? 0 : max) + 1).query(Long.class).single();
        return find(t, id);
    }

    /** null when the tenant has no such template. */
    public synchronized Template update(String t, long id, String name, String system, String expand, String combine) {
        int n = db.sql("UPDATE templates SET name = ?, system = ?, expand = ?, combine = ? WHERE id = ? AND tenant_id = ?")
                .params(name, system, expand, combine, id, t).update();
        return n == 0 ? null : find(t, id);
    }

    /** False when it is the tenant's last template (or not theirs). */
    public synchronized boolean delete(String t, long id) {
        if (count(t) <= 1) return false;
        return db.sql("DELETE FROM templates WHERE id = ? AND tenant_id = ?").params(id, t).update() > 0;
    }
}
