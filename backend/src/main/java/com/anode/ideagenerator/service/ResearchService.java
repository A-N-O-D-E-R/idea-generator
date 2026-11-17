package com.anode.ideagenerator.service;

import static com.anode.ideagenerator.AppError.messageOf;

import com.anode.ideagenerator.AppError;
import com.anode.ideagenerator.Providers;
import com.anode.ideagenerator.Providers.AppSettings;
import com.anode.ideagenerator.Providers.ProviderError;
import com.anode.ideagenerator.Scientist;
import com.anode.ideagenerator.model.Board;
import com.anode.ideagenerator.model.Idea;
import com.anode.ideagenerator.model.Research;
import com.anode.ideagenerator.repository.BoardRepository;
import com.anode.ideagenerator.repository.IdeaRepository;
import com.anode.ideagenerator.repository.ResearchRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The research gate between "best idea" and design: an ai-scientist ideation job is started for an idea, polled
 * ({@code GET /v1/jobs/{id}}) until it ends, and its short hypotheses, related work and risk factors are recorded on
 * the idea. A finished job re-queues the idea for judging, and the judge reads the research: prior work lowers
 * originality, concrete risks lower feasibility. The result goes into idea.json as {@code research}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchService {
    /** A job that has not ended after this long is given up on (ai-scientist's own limit is far shorter). */
    static final Duration GIVE_UP = Duration.ofHours(6);

    private final Ai ai;
    private final BoardRepository boards;
    private final IdeaRepository ideas;
    private final ResearchRepository research;
    private final JudgeService judge;

    private record Access(String url, String key, boolean shared) {}

    private Access access(Board board) {
        AppSettings s = ai.settings(board);
        String key = !s.scientistKey().isEmpty() || s.shared() ? s.scientistKey()
                : Objects.requireNonNullElse(Providers.env.apply("AI_SCIENTIST_API_KEY"), "");
        if (s.scientistUrl().isEmpty() || key.isEmpty()) {
            throw new AppError(400, "no_scientist", "Set the AI Scientist address and API key in the app settings");
        }
        if (s.shared()) {
            try {
                Providers.requirePublic(s.scientistUrl());
            } catch (ProviderError e) {
                throw new AppError(400, e.code, messageOf(e));
            }
        }
        return new Access(s.scientistUrl(), key, s.shared());
    }

    /** Start the ideation job and remember it: the research stays {@code pending} until {@link #refresh} sees it end. */
    public Scientist.Job submit(String t, long ideaId) {
        Idea idea = ideas.require(t, ideaId);
        Board board = boards.require(t, idea.boardId());
        Access a = access(board);
        Scientist.Job job = Scientist.submit(a.url(), a.key(), idea, board.goal(), a.shared());
        research.submitted(t, idea.id(), job.id(), job.url());
        return job;
    }

    public java.util.Optional<Research> get(String t, long ideaId) {
        ideas.require(t, ideaId);
        return research.find(t, ideaId);
    }

    /** Look at the job once: a finished one is recorded and the idea re-judged. Returns the research as it stands. */
    public java.util.Optional<Research> refresh(String t, long ideaId) {
        Idea idea = ideas.require(t, ideaId);
        Research r = research.find(t, ideaId).orElse(null);
        if (r == null || !Research.PENDING.equals(r.status())) return java.util.Optional.ofNullable(r);
        Board board = boards.require(t, idea.boardId());
        Access a = access(board);
        Scientist.State state = Scientist.fetch(a.url(), a.key(), r.jobId(), a.shared());
        if ("DONE".equals(state.status())) {
            Scientist.Findings f = Scientist.findings(state.result());
            if (f.hypotheses().isEmpty() && f.relatedWork().isEmpty()) {
                research.failed(ideaId, "the ideation job finished without ideas");
            } else {
                research.done(ideaId, f.hypotheses(), f.relatedWork(), f.riskFactors());
                judge.queue(t, List.of(ideaId)); // the judge now reads the research
            }
        } else if (state.ended()) {
            research.failed(ideaId, state.status().toLowerCase() + (state.error().isEmpty() ? "" : ": " + state.error()));
        }
        return research.find(t, ideaId);
    }

    /** Polls every pending gate; one failing (a down server, a changed key) never stops the others. */
    @Scheduled(fixedDelayString = "${idea.research.poll-ms:20000}", initialDelayString = "${idea.research.poll-ms:20000}")
    void poll() {
        for (ResearchRepository.Pending p : research.pending()) {
            try {
                if (Duration.between(p.submittedAt(), Instant.now()).compareTo(GIVE_UP) > 0) {
                    research.failed(p.ideaId(), "timed out after " + GIVE_UP.toHours() + " hours");
                } else {
                    refresh(p.tenant(), p.ideaId());
                }
            } catch (RuntimeException e) {
                log.warn("research poll of idea {} failed: {}", p.ideaId(), messageOf(e));
            }
        }
    }
}
