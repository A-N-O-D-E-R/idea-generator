package bio.anode.ideagenerator.service;

import static bio.anode.ideagenerator.AppError.messageOf;

import bio.anode.ideagenerator.AppError;
import bio.anode.ideagenerator.Extract;
import bio.anode.ideagenerator.Js;
import bio.anode.ideagenerator.Llm;
import bio.anode.ideagenerator.Providers;
import bio.anode.ideagenerator.Providers.AppSettings;
import bio.anode.ideagenerator.Providers.ProviderError;
import bio.anode.ideagenerator.Scientist;
import bio.anode.ideagenerator.model.Board;
import bio.anode.ideagenerator.model.Idea;
import bio.anode.ideagenerator.model.Idea.Edge;
import bio.anode.ideagenerator.model.NewIdea;
import bio.anode.ideagenerator.repository.BoardRepository;
import bio.anode.ideagenerator.repository.IdeaRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Creating and changing ideas: seed, expand, combine, announce, spin off, medals, the AI Scientist. New ideas are judged in the background. */
@Service
@RequiredArgsConstructor
public class IdeaService {
    private final Ai ai;
    private final BoardRepository boards;
    private final IdeaRepository ideas;
    private final BoardService boardService;
    private final JudgeService judge;
    private final EventBus bus;

    private List<String> titles(Board board) {
        return ideas.list(board.tenantId(), board.id()).stream().map(Idea::title).toList();
    }

    private static double num(JsonNode v) {
        return Js.isFinite(v) ? v.asDouble() : 0;
    }

    /** Expand now, without queueing the children for judging (the autopilot judges them itself). */
    Map<String, Object> expandNow(String t, long parentId) {
        Idea parent = ideas.require(t, parentId);
        Board board = boards.require(t, parent.boardId());
        List<Llm.Pitch> pitches = ai.call(board, () -> ai.llm(board).expand(ai.modelFor(board, "expand"), board.config().flow(), board.goal(),
                Llm.Pitch.of(parent), titles(board), board.config().lang())).value();
        List<Idea> parents = ideas.parentsOf(t, parent.id());
        List<double[]> spots = Layout.arcPositions(parent, parents.isEmpty() ? null : parents.getFirst(), pitches.size());
        List<Idea> created = new ArrayList<>();
        for (int n = 0; n < pitches.size(); n++) {
            created.add(ideas.insert(t, new NewIdea(board.id(), pitches.get(n).title(), pitches.get(n).pitch(), "expand", null,
                    spots.get(n)[0], spots.get(n)[1], parent.generation() + 1), List.of(parent.id())));
        }
        List<Edge> edges = created.stream().map(c -> new Edge(parent.id(), c.id())).toList();
        bus.ideas(board.id(), created);
        bus.emit(board.id(), "edges", "edges", edges);
        return Js.obj("ideas", created, "edges", edges);
    }

    /** Combine now, without queueing the child for judging. */
    Map<String, Object> combineNow(String t, long boardId, Long aId, Long bId) {
        Board board = boards.require(t, boardId);
        if (aId == null || bId == null || aId.equals(bId)) throw new AppError(400, "bad_pair", "Pick two different ideas");
        Idea a = ideas.require(t, aId);
        Idea c = ideas.require(t, bId);
        if (a.boardId() != board.id() || c.boardId() != board.id()) throw new AppError(400, "bad_pair", "Ideas belong to another board");
        Llm.Pitch idea = ai.call(board, () -> ai.llm(board).combine(ai.modelFor(board, "combine"), board.config().flow(), board.goal(),
                Llm.Pitch.of(a), Llm.Pitch.of(c), titles(board), board.config().lang())).value();
        double dx = c.x() - a.x(), dy = c.y() - a.y(), len = Math.hypot(dx, dy);
        if (len == 0) len = 1; // midpoint, nudged off the line
        Idea created = ideas.insert(t, new NewIdea(board.id(), idea.title(), idea.pitch(), "combine", null,
                (a.x() + c.x()) / 2 + (-dy / len) * 70, (a.y() + c.y()) / 2 + (dx / len) * 70,
                Math.max(a.generation(), c.generation()) + 1), List.of(a.id(), c.id()));
        List<Edge> edges = List.of(new Edge(a.id(), created.id()), new Edge(c.id(), created.id()));
        bus.ideas(board.id(), List.of(created));
        bus.emit(board.id(), "edges", "edges", edges);
        return Js.obj("idea", created, "edges", edges);
    }

    public Map<String, Object> seed(String t, long boardId, JsonNode b) {
        Board board = boards.require(t, boardId);
        String text = Js.cut(Js.trim(Js.str(b.path("text"))), 20_000);
        if (text.isEmpty()) throw new AppError(400, "empty", "Write an idea or paste a link");
        String lang = board.config().lang();
        Board.Flow flow = board.config().flow();
        String url = Extract.asUrl(text);
        String title = Js.cut(text, 120);
        String pitch = "";
        String sourceUrl = null;
        if (url != null) {
            Extract.Page page;
            try {
                page = ai.io().fetchPage().apply(url);
            } catch (RuntimeException e) {
                throw new AppError(422, "url_unreadable", url + ": " + messageOf(e));
            }
            if (page.text().length() < 80) throw new AppError(422, "url_no_text", url + ": too little readable text");
            String source = Stream.of(page.title(), page.text()).filter(s -> s != null && !s.isEmpty()).reduce((x, y) -> x + "\n\n" + y).orElse("");
            Llm.Pitch idea = ai.call(board, () -> ai.llm(board).distill(ai.modelFor(board, "distill"), flow, board.goal(), source, url, lang)).value();
            title = idea.title();
            pitch = idea.pitch();
            sourceUrl = url;
        } else if (text.length() > 160) {
            String src = text;
            Llm.Pitch idea = ai.call(board, () -> ai.llm(board).distill(ai.modelFor(board, "distill"), flow, board.goal(), src, null, lang)).value();
            title = idea.title();
            pitch = idea.pitch();
        }
        Idea idea = ideas.insert(t, new NewIdea(board.id(), title, pitch, "seed", sourceUrl, num(b.path("x")), num(b.path("y")), 0));
        bus.ideas(board.id(), List.of(idea));
        judge.queue(t, List.of(idea.id()));
        return Js.obj("idea", idea);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> expand(String t, long ideaId) {
        Map<String, Object> out = expandNow(t, ideaId);
        judge.queue(t, ((List<Idea>) out.get("ideas")).stream().map(Idea::id).toList());
        return out;
    }

    public Map<String, Object> combine(String t, long boardId, Long aId, Long bId) {
        Map<String, Object> out = combineNow(t, boardId, aId, bId);
        judge.queue(t, List.of(((Idea) out.get("idea")).id()));
        return out;
    }

    public Map<String, Object> setMedal(String t, long ideaId, JsonNode m) {
        ideas.require(t, ideaId);
        boolean isNull = m == null || m.isNull();
        if (!isNull && !(Js.isInt(m) && m.asDouble() >= 0 && m.asDouble() <= 3)) {
            throw new AppError(400, "bad_request", "medalOverride is 0–3 or null");
        }
        Idea idea = ideas.setMedalOverride(t, ideaId, isNull ? null : m.asInt());
        bus.ideas(idea.boardId(), List.of(idea));
        return Js.obj("idea", idea);
    }

    public Map<String, Object> delete(String t, long ideaId) {
        Idea idea = ideas.find(t, ideaId);
        boolean ok = ideas.delete(t, ideaId);
        if (idea != null) bus.emit(idea.boardId(), "removed", "ids", List.of(ideaId));
        return Js.obj("ok", ok);
    }

    /** Announce an idea to other boards: each gets a guest copy that can cross-breed there. */
    public Map<String, Object> announce(String t, long ideaId, JsonNode boardIds) {
        Idea idea = ideas.require(t, ideaId);
        boolean isGuest = "guest".equals(idea.kind()) && idea.originIdeaId() != null && idea.originIdeaId() != 0;
        long originIdeaId = isGuest ? idea.originIdeaId() : idea.id();
        Long originBoardId = isGuest ? idea.originBoardId() : Long.valueOf(idea.boardId());
        Set<Long> wanted = new LinkedHashSet<>();
        if (boardIds != null && boardIds.isString() && boardIds.stringValue().equals("all")) {
            boards.list(t).forEach(x -> wanted.add(x.id()));
        } else {
            for (JsonNode v : Js.arr(boardIds)) if (Js.isInt(v)) wanted.add(v.asLong());
        }
        List<Map<String, Object>> guests = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();
        for (long boardId : wanted) {
            if (boardId == idea.boardId()) continue;
            if (boards.find(t, boardId) == null || ideas.hasGuest(t, boardId, originIdeaId)) {
                skipped.add(boardId);
                continue;
            }
            double[] at = Layout.besideBoard(ideas.list(t, boardId));
            Idea guest = ideas.insert(t, new NewIdea(boardId, idea.title(), idea.pitch(), "guest", idea.sourceUrl(), at[0], at[1], 0,
                    originIdeaId, originBoardId));
            guests.add(Js.obj("boardId", boardId, "idea", guest));
            bus.ideas(boardId, List.of(guest));
        }
        // Judged against each target board's own goal, not the one it came from.
        judge.queue(t, guests.stream().map(g -> ((Idea) g.get("idea")).id()).toList());
        return Js.obj("guests", guests, "skipped", skipped);
    }

    /** Spin an idea off into its own board, to refine it in its own context. */
    public Map<String, Object> spinoff(String t, long ideaId) {
        Idea idea = ideas.require(t, ideaId);
        Board parent = boards.require(t, idea.boardId());
        String goal = Js.cut(idea.pitch() != null && !idea.pitch().isEmpty() ? idea.title() + ": " + idea.pitch() : idea.title(), 600);
        ObjectNode config = Js.tree(parent.config()).put("mode", "manual");
        Board board = boardService.create(t, Js.cut(idea.title(), 120), goal, null, config, idea.id());
        Idea root = ideas.insert(t, new NewIdea(board.id(), idea.title(), idea.pitch(), "seed", idea.sourceUrl(), 0, 0, 0,
                idea.id(), idea.boardId()));
        bus.emit(parent.id(), "spinoff", "ideaId", idea.id(), "boardId", board.id(), "title", board.title());
        judge.queue(t, List.of(root.id()));
        return Js.obj("board", boards.find(t, board.id()), "idea", root);
    }

    /** Send an idea to perso/ai-scientist as an ideation job: {job: {id, url, status}}. */
    public Map<String, Object> toScientist(String t, long ideaId) {
        Idea idea = ideas.require(t, ideaId);
        Board board = boards.require(t, idea.boardId());
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
        return Js.obj("job", Scientist.submit(s.scientistUrl(), key, idea, board.goal(), s.shared()));
    }
}
