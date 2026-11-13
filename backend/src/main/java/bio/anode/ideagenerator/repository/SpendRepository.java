package bio.anode.ideagenerator.repository;

import bio.anode.ideagenerator.model.Reports.Spent;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The spend ledger, per tenant, board and local day. */
@Repository
@RequiredArgsConstructor
public class SpendRepository {
    private final JdbcClient db;

    /** YYYY-MM-DD in local time: budgets reset at local midnight. */
    public static String localDay(LocalDate d) {
        return d.toString();
    }

    public static String localDay() {
        return localDay(LocalDate.now());
    }

    public void add(String t, long boardId, double usd) {
        db.sql("""
                        INSERT INTO spend (tenant_id, board_id, day, usd, calls) VALUES (?, ?, ?, ?, 1)
                         ON CONFLICT (tenant_id, board_id, day) DO UPDATE SET usd = spend.usd + excluded.usd, calls = spend.calls + 1""")
                .params(t, boardId, localDay(), usd > 0 ? usd : 0).update();
    }

    /** Spent today on one board, or (boardId null) across all the tenant's boards. */
    public double today(String t, Long boardId) {
        Double v = boardId == null
                ? db.sql("SELECT SUM(usd) FROM spend WHERE tenant_id = ? AND day = ?").params(t, localDay()).query(Double.class).optional().orElse(null)
                : db.sql("SELECT usd FROM spend WHERE tenant_id = ? AND board_id = ? AND day = ?").params(t, boardId, localDay())
                        .query(Double.class).optional().orElse(null);
        return v == null ? 0 : v;
    }

    /** Spent in the last `days` local days, today included. */
    public double lastDays(String t, int days) {
        Double v = db.sql("SELECT SUM(usd) AS usd FROM spend WHERE tenant_id = ? AND day >= ?")
                .params(t, localDay(LocalDate.now().minusDays(days - 1))).query(Double.class).optional().orElse(null);
        return v == null ? 0 : v;
    }

    public Spent total(String t) {
        return db.sql("SELECT SUM(usd) AS usd, SUM(calls) AS calls FROM spend WHERE tenant_id = ?").param(t)
                .query((r, n) -> new Spent(r.getDouble("usd"), r.getLong("calls"))).single();
    }
}
