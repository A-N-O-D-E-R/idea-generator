package bio.anode.ideagenerator.service;

import bio.anode.ideagenerator.Evolution;
import bio.anode.ideagenerator.Score;
import bio.anode.ideagenerator.model.Board;
import bio.anode.ideagenerator.model.Idea;
import bio.anode.ideagenerator.repository.BoardRepository;
import bio.anode.ideagenerator.repository.IdeaRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** One autopilot step on a board. */
@Service
@RequiredArgsConstructor
public class EvolutionService {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Step(String did, String reason, List<Long> ids) {}

    private final BoardRepository boards;
    private final IdeaRepository ideas;
    private final IdeaService ideaService;
    private final JudgeService judge;

    /**
     * One autopilot step: judge what is unjudged, otherwise breed (combine by
     * tournament selection) or mutate (expand a strong idea). Waits for its own
     * judgements so the next step sees the scores.
     */
    @SuppressWarnings("unchecked")
    public Step step(String t, long boardId) {
        Board board = boards.require(t, boardId);
        List<Idea> all = ideas.list(t, boardId);
        if (all.isEmpty()) return new Step("idle", "empty", null);
        List<Long> unjudged = all.stream()
                .filter(i -> !Score.isJudged(i) && i.medalOverride() == null && !judge.judging.contains(i.id()))
                .limit(3).map(Idea::id).toList();
        if (!unjudged.isEmpty()) {
            judge.judgeAll(t, unjudged);
            return new Step("judge", null, unjudged);
        }
        Evolution.Move move = Evolution.nextMove(all, ideas.edges(t, boardId), judge.judging, board.config().diversity());
        return switch (move) {
            case null -> new Step("idle", "nothing_to_do", null);
            case Evolution.Expand e -> {
                List<Long> ids = ((List<Idea>) ideaService.expandNow(t, e.idea().id()).get("ideas")).stream().map(Idea::id).toList();
                judge.judgeAll(t, ids);
                yield new Step("expand", null, ids);
            }
            case Evolution.Combine c -> {
                long id = ((Idea) ideaService.combineNow(t, boardId, c.a().id(), c.b().id()).get("idea")).id();
                judge.judgeAll(t, List.of(id));
                yield new Step("combine", null, List.of(id));
            }
        };
    }
}
