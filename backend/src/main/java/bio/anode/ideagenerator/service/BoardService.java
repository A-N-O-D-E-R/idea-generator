package bio.anode.ideagenerator.service;

import bio.anode.ideagenerator.AppError;
import bio.anode.ideagenerator.Elo;
import bio.anode.ideagenerator.Js;
import bio.anode.ideagenerator.Llm;
import bio.anode.ideagenerator.Markdown;
import bio.anode.ideagenerator.Score;
import bio.anode.ideagenerator.model.Board;
import bio.anode.ideagenerator.model.Idea;
import bio.anode.ideagenerator.model.Idea.Position;
import bio.anode.ideagenerator.repository.BoardRepository;
import bio.anode.ideagenerator.repository.IdeaRepository;
import bio.anode.ideagenerator.repository.LeadRepository;
import bio.anode.ideagenerator.repository.SpendRepository;
import bio.anode.ideagenerator.repository.TemplateRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Boards: create, read, change, duel, summarize, export. */
@Service
@RequiredArgsConstructor
public class BoardService {
    private final Ai ai;
    private final BoardRepository boards;
    private final IdeaRepository ideas;
    private final LeadRepository leads;
    private final TemplateRepository templates;
    private final SpendRepository spend;
    private final JudgeService judge;
    private final EventBus bus;

    /** Boards, newest activity first, each with its ideaCount. */
    public List<ObjectNode> list(String t) {
        return boards.list(t).stream().map(b -> Js.tree(b).put("ideaCount", ideas.count(b.id()))).toList();
    }

    /** A new board: its flow comes from the template (default: the tenant's first), then `config` is merged on top. */
    public Board create(String t, String title, String goal, Long templateId, JsonNode config, Long originIdeaId) {
        Board.Config c = Board.mergeConfig(Board.defaultConfig(templates.findOrFirst(t, templateId)), config);
        // A spin-off's origin must be one of the tenant's own ideas.
        Long origin = originIdeaId != null && ideas.find(t, originIdeaId) != null ? originIdeaId : null;
        return boards.insert(t, title, goal, c, origin);
    }

    public boolean exists(String t, long id) {
        return boards.find(t, id) != null;
    }

    /** The whole board for the browser; `autopilot` is the board's autopilot status. */
    public Map<String, Object> view(String t, long id, Object autopilot) {
        Board board = boards.require(t, id);
        List<Idea> all = ideas.list(t, board.id());
        return Js.obj("board", board, "ideas", all, "edges", ideas.edges(t, board.id()), "leads", leads.list(t, board.id()),
                "spinoffs", boards.spinoffs(t, board.id()),
                "judging", all.stream().filter(i -> judge.judging.contains(i.id())).map(Idea::id).toList(),
                "autopilot", autopilot, "spentToday", spend.today(t, board.id()));
    }

    public Map<String, Object> update(String t, long boardId, String title, String goal, JsonNode config) {
        boards.require(t, boardId);
        Board board = boards.update(t, boardId, title, goal, config);
        bus.emit(boardId, "board", "board", board);
        return Js.obj("board", board);
    }

    public boolean delete(String t, long id) {
        return boards.delete(t, id);
    }

    public int savePositions(String t, long boardId, List<Position> positions) {
        boards.require(t, boardId);
        ideas.savePositions(t, boardId, positions);
        return positions.size();
    }

    public String exportMarkdown(String t, long boardId, String lang) {
        Board board = boards.require(t, boardId);
        return Markdown.board(board, ideas.list(t, board.id()), ideas.edges(t, board.id()), lang);
    }

    private static int fitnessOr(Idea i) {
        Integer f = Score.fitness(i);
        return f == null ? -1 : f;
    }

    /** One Elo duel between two of the board's best judged ideas: {a, b, scoreA, reasons, ideas}. */
    public Map<String, Object> duel(String t, long boardId) {
        Board board = boards.require(t, boardId);
        Idea[] pair = Elo.pickPair(ideas.list(t, board.id()), ThreadLocalRandom.current());
        if (pair == null) throw new AppError(400, "too_few", "A duel needs at least two judged ideas");
        Idea a = pair[0], b = pair[1];
        Elo.Duel d = ai.call(board, () -> Elo.duel(ai.chat(board), ai.modelFor(board, "judge"), board.goal(), a, b, board.config().judgeGuidance())).value();
        double[] r = Elo.update(Elo.rating(a), Elo.rating(b), d.scoreA());
        ideas.saveDuel(t, a.id(), r[0], b.id(), r[1]);
        List<Idea> both = List.of(ideas.require(t, a.id()), ideas.require(t, b.id()));
        bus.ideas(board.id(), both);
        return Js.obj("a", a.id(), "b", b.id(), "scoreA", d.scoreA(), "reasons", d.reasons(), "ideas", both);
    }

    public Map<String, Object> summarize(String t, long boardId) {
        Board board = boards.require(t, boardId);
        List<Llm.SummaryIdea> all = ideas.list(t, board.id()).stream()
                .sorted(Comparator.comparingInt(BoardService::fitnessOr).reversed())
                .map(i -> new Llm.SummaryIdea(i.title(), i.pitch(), i.kind(), Score.scoreSum(i), i.reason()))
                .toList();
        if (all.size() < 2) throw new AppError(400, "too_few", "A summary needs at least two ideas");
        String lang = board.config().lang();
        ObjectNode summary = ai.call(board, () -> ai.llm(board).summarize(ai.modelFor(board, "summary"), board.goal(), lang, all)).value();
        summary.put("ideaCount", all.size()).put("lang", lang).put("createdAt", Js.isoNow());
        Board saved = boards.setSummary(t, board.id(), summary);
        bus.emit(board.id(), "board", "board", saved);
        return Js.obj("board", saved);
    }
}
