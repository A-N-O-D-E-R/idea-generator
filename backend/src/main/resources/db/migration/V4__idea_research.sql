-- The research gate: an ai-scientist ideation job started for an idea, and what it found out. One row per idea.
-- status: pending (job running), done (hypotheses etc. are filled in), failed (error says why).
-- novelty_verdict: novel | incremental | known | unclear, derived from the judge's originality score once it has
-- seen the related work. Written into idea.json as `research` (digital-factory idea-brief.schema.json).
CREATE TABLE idea_research (
  idea_id bigint PRIMARY KEY REFERENCES ideas(id) ON DELETE CASCADE,
  job_id text NOT NULL,
  job_url text NOT NULL,
  status text NOT NULL CHECK (status IN ('pending', 'done', 'failed')),
  hypotheses text NOT NULL DEFAULT '[]',
  related_work text NOT NULL DEFAULT '',
  risk_factors text NOT NULL DEFAULT '[]',
  novelty_verdict text NOT NULL DEFAULT 'unclear' CHECK (novelty_verdict IN ('novel', 'incremental', 'known', 'unclear')),
  error text,
  submitted_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz
);
CREATE INDEX idea_research_pending ON idea_research(status) WHERE status = 'pending';
