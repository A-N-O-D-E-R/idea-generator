package bio.anode.ideagenerator.service;

import static bio.anode.ideagenerator.AppError.codeOf;
import static bio.anode.ideagenerator.AppError.messageOf;
import static bio.anode.ideagenerator.service.EventBus.event;

import bio.anode.ideagenerator.Js;
import bio.anode.ideagenerator.Llm;
import bio.anode.ideagenerator.Score;
import bio.anode.ideagenerator.model.Board;
import bio.anode.ideagenerator.model.Idea;
import bio.anode.ideagenerator.repository.BoardRepository;
import bio.anode.ideagenerator.repository.IdeaRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Judging: one idea now, or a small background queue (at most three at once), so announced guests get judged even on
 * boards nobody has open. A gold idea becomes a Claude Code project if its board allows it.
 */
@Service
@RequiredArgsConstructor
public class JudgeService {
    private static final List<String> PROJECT_KEYS = List.of("workspace", "workspaceExisted", "workspaceError",
            "workspaceDuplicateOf", "workspaceRelated", "workspaceRepos", "workspacePapers");

    /** Ids being judged right now (the board view shows them). */
    public final Set<Long> judging = ConcurrentHashMap.newKeySet();

    private final Ai ai;
    private final BoardRepository boards;
    private final IdeaRepository ideas;
    private final ProjectService projects;
    private final EventBus bus;

    private final Object queueLock = new Object();
    private record Queued(String tenant, long id) {}

    private final ArrayDeque<Queued> queue = new ArrayDeque<>();
    private int active;

    private void setJudging(long boardId, List<Long> ids, boolean on) {
        for (long id : ids) {
            if (on) judging.add(id);
            else judging.remove(id);
        }
        bus.emit(boardId, "judging", "ids", ids, "on", on);
    }

    /** Judge one idea now: {idea, project?, projectError?}. */
    public Map<String, Object> judge(String t, long ideaId) {
        Idea idea = ideas.require(t, ideaId);
        Board board = boards.require(t, idea.boardId());
        setJudging(board.id(), List.of(idea.id()), true);
        try {
            Llm.Judgement j = ai.call(board, () -> ai.llm(board).judge(ai.modelFor(board, "judge"), board.goal(), Llm.Pitch.of(idea),
                    board.config().lang(), board.config().judgeGuidance(), ideas.keywordVocabulary(t))).value();
            ideas.saveJudgement(t, idea.id(), j);
            Map<String, Object> out = Js.obj("idea", ideas.setKeywords(t, idea.id(), j.keywords()));
            Idea judged = (Idea) out.get("idea");
            // The judge itself turns gold into a Claude Code project, if the board allows it.
            if (board.config().autoProjectOnGold() && Integer.valueOf(3).equals(Score.medal(judged))
                    && (judged.projectPath() == null || judged.projectPath().isEmpty())) {
                try {
                    Map<String, Object> p = projects.create(t, judged);
                    out.put("idea", p.get("idea"));
                    out.put("project", p.get("path"));
                    Map<String, Object> ev = event("project", "ideaId", idea.id(), "path", p.get("path"), "byJudge", true);
                    for (String k : PROJECT_KEYS) if (p.containsKey(k)) ev.put(k, p.get(k));
                    bus.emit(board.id(), ev);
                } catch (RuntimeException e) {
                    out.put("projectError", messageOf(e));
                }
            }
            bus.ideas(board.id(), List.of((Idea) out.get("idea")));
            return out;
        } finally {
            setJudging(board.id(), List.of(idea.id()), false);
        }
    }

    private void pump() {
        synchronized (queueLock) {
            while (active < 3 && !queue.isEmpty()) {
                Queued q = queue.poll();
                active++;
                Thread.ofVirtual().name("judge-" + q.id()).start(() -> runQueued(q.tenant(), q.id()));
            }
        }
    }

    private void runQueued(String t, long id) {
        try {
            judge(t, id);
        } catch (RuntimeException e) {
            Idea i = ideas.find(t, id);
            if (i != null && i.boardId() != 0) {
                bus.emit(i.boardId(), "error", "code", codeOf(e, "judge_failed"), "message", messageOf(e), "ideaId", id);
            }
        } finally {
            synchronized (queueLock) {
                active--;
                pump();
                if (active == 0 && queue.isEmpty()) queueLock.notifyAll();
            }
        }
    }

    public void queue(String t, List<Long> ids) {
        synchronized (queueLock) {
            for (long id : ids) if (!judging.contains(id) && queue.stream().noneMatch(q -> q.id() == id)) queue.add(new Queued(t, id));
        }
        pump();
    }

    /** Returns when no judgement is queued or running (tests, shutdown). */
    public void idle() {
        synchronized (queueLock) {
            while (active > 0 || !queue.isEmpty()) {
                try {
                    queueLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Judge in parallel; fail only if every judgement failed. */
    void judgeAll(String t, List<Long> ids) {
        List<RuntimeException> errors = new ArrayList<>();
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = ids.stream().<Future<?>>map(id -> ex.submit(() -> judge(t, id))).toList();
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    errors.add(e.getCause() instanceof RuntimeException re ? re : new RuntimeException(e.getCause()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
        }
        if (!ids.isEmpty() && errors.size() == ids.size()) throw errors.getFirst();
    }
}
