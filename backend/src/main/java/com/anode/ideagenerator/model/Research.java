package com.anode.ideagenerator.model;

import java.util.List;

/**
 * What ai-scientist found out about an idea (the research gate). {@code status}: pending, done or failed.
 * Serialises as the {@code research} block of idea.json, see {@link #block()}.
 */
public record Research(long ideaId, String jobId, String jobUrl, String status, List<String> hypotheses, String relatedWork,
                       List<String> riskFactors, String noveltyVerdict, String error) {
    public static final String PENDING = "pending", DONE = "done", FAILED = "failed";

    public boolean done() {
        return DONE.equals(status);
    }

    /** The idea-brief.schema.json {@code research} object; only for a finished gate. */
    public java.util.Map<String, Object> block() {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("hypotheses", hypotheses);
        m.put("relatedWork", relatedWork);
        m.put("riskFactors", riskFactors);
        m.put("noveltyVerdict", noveltyVerdict);
        m.put("jobUrl", jobUrl);
        return m;
    }

    /** The judge's view of it: what is already known and what could go wrong. */
    public String forJudge() {
        var sb = new StringBuilder("Research already done on this idea by an AI scientist (literature search):\n");
        hypotheses.forEach(h -> sb.append("- Hypothesis: ").append(h).append('\n'));
        if (!relatedWork.isBlank()) sb.append("Related work:\n").append(relatedWork).append('\n');
        riskFactors.forEach(r -> sb.append("- Risk: ").append(r).append('\n'));
        return sb.toString().strip();
    }
}
