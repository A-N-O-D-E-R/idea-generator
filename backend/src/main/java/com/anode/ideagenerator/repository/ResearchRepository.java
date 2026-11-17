package com.anode.ideagenerator.repository;

import com.anode.ideagenerator.Js;
import com.anode.ideagenerator.model.Research;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;

/** The research gate's rows. Ideas hang off boards, so every read by id also checks the board's tenant. */
@Repository
@RequiredArgsConstructor
public class ResearchRepository {
    private static final String OWN = "FROM idea_research r JOIN ideas i ON i.id = r.idea_id JOIN boards b ON b.id = i.board_id AND b.tenant_id = ? ";
    private static final String COLUMNS = "r.idea_id, r.job_id, r.job_url, r.status, r.hypotheses, r.related_work, r.risk_factors, r.novelty_verdict, r.error ";

    private final JdbcClient db;

    private static Research map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Research(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), strings(rs.getString(5)), rs.getString(6),
                strings(rs.getString(7)), rs.getString(8), rs.getString(9));
    }

    private static List<String> strings(String json) {
        return Js.arr(Js.parse(json)).stream().map(JsonNode::asString).toList();
    }

    public Optional<Research> find(String tenant, long ideaId) {
        return db.sql("SELECT " + COLUMNS + OWN + "WHERE r.idea_id = ?").params(tenant, ideaId).query(ResearchRepository::map).optional();
    }

    /** A new job for this idea replaces any earlier research on it. */
    public void submitted(String tenant, long ideaId, String jobId, String jobUrl) {
        db.sql("""
                INSERT INTO idea_research (idea_id, job_id, job_url, status)
                SELECT i.id, ?, ?, 'pending' FROM ideas i JOIN boards b ON b.id = i.board_id AND b.tenant_id = ? WHERE i.id = ?
                ON CONFLICT (idea_id) DO UPDATE SET job_id = EXCLUDED.job_id, job_url = EXCLUDED.job_url, status = 'pending',
                  hypotheses = '[]', related_work = '', risk_factors = '[]', novelty_verdict = 'unclear', error = NULL,
                  submitted_at = now(), completed_at = NULL""").params(jobId, jobUrl, tenant, ideaId).update();
    }

    public void done(long ideaId, List<String> hypotheses, String relatedWork, List<String> riskFactors) {
        db.sql("""
                UPDATE idea_research SET status = 'done', hypotheses = ?, related_work = ?, risk_factors = ?, novelty_verdict = 'unclear',
                  error = NULL, completed_at = now() WHERE idea_id = ? AND status = 'pending'""")
                .params(Js.stringify(hypotheses), relatedWork, Js.stringify(riskFactors), ideaId).update();
    }

    public void failed(long ideaId, String error) {
        db.sql("UPDATE idea_research SET status = 'failed', error = ?, completed_at = now() WHERE idea_id = ? AND status = 'pending'")
                .params(error, ideaId).update();
    }

    public void verdict(long ideaId, String verdict) {
        db.sql("UPDATE idea_research SET novelty_verdict = ? WHERE idea_id = ? AND status = 'done'").params(verdict, ideaId).update();
    }

    /** Pending rows with their tenant and age, oldest first (the poller's work list). */
    public record Pending(String tenant, long ideaId, java.time.Instant submittedAt) {}

    public List<Pending> pending() {
        return db.sql("SELECT b.tenant_id, r.idea_id, r.submitted_at FROM idea_research r JOIN ideas i ON i.id = r.idea_id JOIN boards b ON b.id = i.board_id "
                        + "WHERE r.status = 'pending' ORDER BY r.submitted_at")
                .query((rs, n) -> new Pending(rs.getString(1), rs.getLong(2), rs.getTimestamp(3).toInstant())).list();
    }
}
