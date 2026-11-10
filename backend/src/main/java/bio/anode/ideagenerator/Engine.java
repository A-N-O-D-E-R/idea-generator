package bio.anode.ideagenerator;

import static bio.anode.ideagenerator.AppError.codeOf;
import static bio.anode.ideagenerator.AppError.messageOf;

import bio.anode.ideagenerator.Idea.Edge;
import bio.anode.ideagenerator.Llm.Out;
import bio.anode.ideagenerator.Providers.AppSettings;
import bio.anode.ideagenerator.Providers.Chat;
import bio.anode.ideagenerator.Providers.ProviderError;
import bio.anode.ideagenerator.Repo.Lead;
import bio.anode.ideagenerator.Repo.NewIdea;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every operation on boards and ideas (upstream engine.ts), used by both the
 * HTTP routes and the autopilot. New ideas are judged in the background (a
 * small queue, at most three at once), so announced guests get judged even on
 * boards nobody has open. Every change is published on a per-board bus that
 * the browser follows over SSE.
 */
public class Engine {

    /** Everything that leaves the process; tests pass fakes. */
    /** chat: a chat routed with the given (tenant's) settings. */
    public record Io(Function<AppSettings, Chat> chat, Function<String, Extract.Page> fetchPage, Function<String, List<Leads.Paper>> fetchPapers,
                     Consumer<String> reveal, boolean gitInit, Function<String, List<Leads.Paper>> searchPapers) {
        /** shared: a shared server (ideagen.auth=oauth) only fetches public addresses. */
        public static Io real(boolean shared) {
            return new Io(Providers::makeChat, url -> Extract.fetchPage(url, shared), Leads::fetchPapers, Project::reveal, true, Leads::searchPapers);
        }
    }

    /** Per-board event bus. Events are maps with a "type" first. */
    public static final class Bus {
        private final Map<Long, Set<Consumer<Map<String, Object>>>> subs = new ConcurrentHashMap<>();

        public Runnable on(long boardId, Consumer<Map<String, Object>> fn) {
            subs.computeIfAbsent(boardId, k -> new CopyOnWriteArraySet<>()).add(fn);
            return () -> {
                Set<Consumer<Map<String, Object>>> s = subs.get(boardId);
                if (s != null) s.remove(fn);
            };
        }

        public void emit(long boardId, Map<String, Object> e) {
            for (Consumer<Map<String, Object>> fn : subs.getOrDefault(boardId, Set.of())) {
                try {
                    fn.accept(e);
                } catch (RuntimeException dead) {
                    // a dead subscriber must not break the others
                }
            }
        }
    }

    public static Map<String, Object> event(String type, Object... kv) {
        Object[] all = new Object[kv.length + 2];
        all[0] = "type";
        all[1] = type;
        System.arraycopy(kv, 0, all, 2, kv.length);
        return Js.obj(all);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Step(String did, String reason, List<Long> ids) {}

    public final Bus bus = new Bus();
    public final Set<Long> judging = ConcurrentHashMap.newKeySet();

    private final Repo repo;
    /** tenant id -> that tenant's app settings */
    private final Function<String, AppSettings> settings;
    private final Io io;

    public Engine(Repo repo, Function<String, AppSettings> settings, Io io) {
        this.repo = repo;
        this.settings = settings;
        this.io = io;
    }

    private AppSettings settings(Board board) {
        return settings.apply(board.tenantId());
    }

    private Chat chat(Board board) {
        return io.chat().apply(settings(board));
    }

    private Llm llm(Board board) {
        return new Llm(chat(board));
    }

    /** Arc of n children pointing away from where the parent came from. */
    static List<double[]> arcPositions(Idea parent, Idea grandparent, int n) {
        double dir = grandparent != null ? Math.atan2(parent.y() - grandparent.y(), parent.x() - grandparent.x()) : Math.random() * Math.PI * 2;
        double spread = grandparent != null ? Math.PI * 2 / 3 : Math.PI * 2;
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = grandparent != null ? dir - spread / 2 + spread * (i + 0.5) / n : dir + spread * i / n;
            out.add(new double[]{parent.x() + Math.cos(a) * 190, parent.y() + Math.sin(a) * 190});
        }
        return out;
    }

    /** Where a newcomer (guest, lead) lands: just right of what is already on the board. */
    static double[] besideBoard(List<Idea> ideas) {
        if (ideas.isEmpty()) return new double[]{0, 0};
        double maxX = ideas.stream().mapToDouble(Idea::x).max().getAsDouble();
        double meanY = ideas.stream().mapToDouble(Idea::y).sum() / ideas.size();
        return new double[]{maxX + 240, meanY + (Math.random() - 0.5) * 160};
    }

    private Board mustBoard(String t, long id) {
        Board b = repo.getBoard(t, id);
        if (b == null) throw new AppError(404, "not_found", "Board not found");
        return b;
    }

    private Idea mustIdea(String t, long id) {
        Idea i = repo.getIdea(t, id);
        if (i == null) throw new AppError(404, "not_found", "Idea not found");
        return i;
    }

    public String modelFor(Board board, String role) {
        String m = board.config().models().get(role);
        return m != null && !m.isEmpty() ? m : settings(board).models().get(role);
    }

    private List<String> titles(Board board) {
        return repo.listIdeas(board.tenantId(), board.id()).stream().map(Idea::title).toList();
    }

    private void emitIdeas(long boardId, List<Idea> ideas) {
        if (!ideas.isEmpty()) bus.emit(boardId, event("ideas", "ideas", ideas));
    }

    /** Every LLM call: spend lands on the board's ledger; failures become 502s that keep the provider's code. */
    private <T> Out<T> call(Board board, Supplier<Out<T>> fn) {
        return call(board.tenantId(), board.id(), fn);
    }

    private <T> Out<T> call(String t, long boardId, Supplier<Out<T>> fn) {
        try {
            Out<T> out = fn.get();
            repo.addSpend(t, boardId, out.costUsd());
            return out;
        } catch (AppError e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AppError(502, e instanceof ProviderError p ? p.code : "llm_failed", messageOf(e));
        }
    }

    // ---------------------------------------------------------------- judging

    private void setJudging(long boardId, List<Long> ids, boolean on) {
        for (long id : ids) {
            if (on) judging.add(id);
            else judging.remove(id);
        }
        bus.emit(boardId, event("judging", "ids", ids, "on", on));
    }

    /**
     * The idea's project folder, or null. On a shared server projectPath holds only the folder name, inside the tenant's
     * projects folder, so no server path reaches the JSON (getFileName also strips anything that tries to climb out).
     */
    static Path projectFolder(AppSettings s, Idea idea) {
        if (idea.projectPath() == null || idea.projectPath().isEmpty()) return null;
        Path p = Path.of(idea.projectPath());
        return s.shared() ? Path.of(s.projectsRoot()).resolve(p.getFileName().toString()) : p;
    }

    private Map<String, Object> createProject(String t, Idea idea) {
        Board board = mustBoard(t, idea.boardId());
        Path existing = projectFolder(settings(board), idea);
        if (existing != null && Files.exists(existing)) {
            return Js.obj("idea", idea, "path", idea.projectPath(), "existed", true);
        }
        List<Idea> lineage = repo.lineage(t, idea.id());
        String lang = board.config().lang();
        Project.Brief brief = call(board, () -> Project.writeBrief(chat(board), modelFor(board, "brief"), idea, board.goal(), lineage, lang)).value();
        String path;
        try {
            path = Project.writeProject(settings(board).projectsRoot(), brief, idea, board.goal(), lineage, lang, io.gitInit());
            if (settings(board).shared()) path = Path.of(path).getFileName().toString();
        } catch (Exception e) {
            throw new AppError(500, "project_failed", messageOf(e));
        }
        Idea saved = repo.setProjectPath(t, idea.id(), path);
        emitIdeas(board.id(), List.of(saved));
        Map<String, Object> out = Js.obj("idea", saved, "path", path, "existed", false);
        String workspace = settings(board).workspaceRoot();
        if (!workspace.isEmpty()) {
            // The project is made either way; filing into the workspace is a bonus that may fail on its own.
            try {
                List<Workspace.Row> rows = Workspace.rows(Workspace.readIndex(workspace));
                List<Workspace.Repo> repos = Workspace.scanRepos(settings(board).reposRoots());
                List<Leads.Paper> papers = io.searchPapers().apply(brief.name() + " " + brief.oneLiner());
                Workspace.Match match = call(board, () -> Workspace.match(chat(board), modelFor(board, "brief"), brief, saved, rows, repos, papers)).value();
                Workspace.Filed f = Workspace.file(workspace, brief, saved, board.goal(), lineage, path, Js.utcToday(), match);
                out.put("workspace", f.path());
                out.put("workspaceExisted", f.existed());
                if (match.duplicateOf() != null) out.put("workspaceDuplicateOf", match.duplicateOf());
                out.put("workspaceRelated", f.related());
                out.put("workspaceRepos", match.repos().stream().map(Workspace.RepoHit::path).toList());
                out.put("workspacePapers", match.papers().size());
            } catch (IOException | RuntimeException e) {
                out.put("workspaceError", messageOf(e));
            }
        }
        return out;
    }

    /** Judge one idea now: {idea, project?, projectError?}. */
    public Map<String, Object> judge(String t, long ideaId) {
        Idea idea = mustIdea(t, ideaId);
        Board board = mustBoard(t, idea.boardId());
        setJudging(board.id(), List.of(idea.id()), true);
        try {
            Llm.Judgement j = call(board, () -> llm(board).judge(modelFor(board, "judge"), board.goal(), Llm.Pitch.of(idea),
                    board.config().lang(), board.config().judgeGuidance(), repo.keywordVocabulary(t))).value();
            repo.saveJudgement(t, idea.id(), j);
            Map<String, Object> out = Js.obj("idea", repo.setKeywords(t, idea.id(), j.keywords()));
            Idea judged = (Idea) out.get("idea");
            // The judge itself turns gold into a Claude Code project, if the board allows it.
            if (board.config().autoProjectOnGold() && Integer.valueOf(3).equals(Score.medal(judged))
                    && (judged.projectPath() == null || judged.projectPath().isEmpty())) {
                try {
                    Map<String, Object> p = createProject(t, judged);
                    out.put("idea", p.get("idea"));
                    out.put("project", p.get("path"));
                    Map<String, Object> ev = event("project", "ideaId", idea.id(), "path", p.get("path"), "byJudge", true);
                    for (String k : List.of("workspace", "workspaceExisted", "workspaceError", "workspaceDuplicateOf", "workspaceRelated", "workspaceRepos", "workspacePapers")) if (p.containsKey(k)) ev.put(k, p.get(k));
                    bus.emit(board.id(), ev);
                } catch (RuntimeException e) {
                    out.put("projectError", messageOf(e));
                }
            }
            emitIdeas(board.id(), List.of((Idea) out.get("idea")));
            return out;
        } finally {
            setJudging(board.id(), List.of(idea.id()), false);
        }
    }

    // Background queue: at most three judgements at once.
    private final Object queueLock = new Object();
    private record Queued(String tenant, long id) {}

    private final ArrayDeque<Queued> queue = new ArrayDeque<>();
    private int active;

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
            Idea i = repo.getIdea(t, id);
            if (i != null && i.boardId() != 0) {
                bus.emit(i.boardId(), event("error", "code", codeOf(e, "judge_failed"), "message", messageOf(e), "ideaId", id));
            }
        } finally {
            synchronized (queueLock) {
                active--;
                pump();
                if (active == 0 && queue.isEmpty()) queueLock.notifyAll();
            }
        }
    }

    public void queueJudge(String t, List<Long> ids) {
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

    // ---------------------------------------------------------------- creating ideas

    private Map<String, Object> doExpand(String t, long parentId) {
        Idea parent = mustIdea(t, parentId);
        Board board = mustBoard(t, parent.boardId());
        List<Llm.Pitch> ideas = call(board, () -> llm(board).expand(modelFor(board, "expand"), board.config().flow(), board.goal(),
                Llm.Pitch.of(parent), titles(board), board.config().lang())).value();
        List<Idea> parents = repo.parentsOf(t, parent.id());
        List<double[]> spots = arcPositions(parent, parents.isEmpty() ? null : parents.getFirst(), ideas.size());
        List<Idea> created = new ArrayList<>();
        for (int n = 0; n < ideas.size(); n++) {
            created.add(repo.insertIdea(t, new NewIdea(board.id(), ideas.get(n).title(), ideas.get(n).pitch(), "expand", null,
                    spots.get(n)[0], spots.get(n)[1], parent.generation() + 1), List.of(parent.id())));
        }
        List<Edge> edges = created.stream().map(c -> new Edge(parent.id(), c.id())).toList();
        emitIdeas(board.id(), created);
        bus.emit(board.id(), event("edges", "edges", edges));
        return Js.obj("ideas", created, "edges", edges);
    }

    private Map<String, Object> doCombine(String t, long boardId, Long aId, Long bId) {
        Board board = mustBoard(t, boardId);
        if (aId == null || bId == null || aId.equals(bId)) throw new AppError(400, "bad_pair", "Pick two different ideas");
        Idea a = mustIdea(t, aId);
        Idea c = mustIdea(t, bId);
        if (a.boardId() != board.id() || c.boardId() != board.id()) throw new AppError(400, "bad_pair", "Ideas belong to another board");
        Llm.Pitch idea = call(board, () -> llm(board).combine(modelFor(board, "combine"), board.config().flow(), board.goal(),
                Llm.Pitch.of(a), Llm.Pitch.of(c), titles(board), board.config().lang())).value();
        double dx = c.x() - a.x(), dy = c.y() - a.y(), len = Math.hypot(dx, dy);
        if (len == 0) len = 1; // midpoint, nudged off the line
        Idea created = repo.insertIdea(t, new NewIdea(board.id(), idea.title(), idea.pitch(), "combine", null,
                (a.x() + c.x()) / 2 + (-dy / len) * 70, (a.y() + c.y()) / 2 + (dx / len) * 70,
                Math.max(a.generation(), c.generation()) + 1), List.of(a.id(), c.id()));
        List<Edge> edges = List.of(new Edge(a.id(), created.id()), new Edge(c.id(), created.id()));
        emitIdeas(board.id(), List.of(created));
        bus.emit(board.id(), event("edges", "edges", edges));
        return Js.obj("idea", created, "edges", edges);
    }

    private static double num(JsonNode v) {
        return Js.isFinite(v) ? v.asDouble() : 0;
    }

    public Map<String, Object> seed(String t, long boardId, JsonNode b) {
        Board board = mustBoard(t, boardId);
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
                page = io.fetchPage().apply(url);
            } catch (RuntimeException e) {
                throw new AppError(422, "url_unreadable", url + ": " + messageOf(e));
            }
            if (page.text().length() < 80) throw new AppError(422, "url_no_text", url + ": too little readable text");
            String source = Stream.of(page.title(), page.text()).filter(s -> s != null && !s.isEmpty()).reduce((x, y) -> x + "\n\n" + y).orElse("");
            Llm.Pitch idea = call(board, () -> llm(board).distill(modelFor(board, "distill"), flow, board.goal(), source, url, lang)).value();
            title = idea.title();
            pitch = idea.pitch();
            sourceUrl = url;
        } else if (text.length() > 160) {
            String src = text;
            Llm.Pitch idea = call(board, () -> llm(board).distill(modelFor(board, "distill"), flow, board.goal(), src, null, lang)).value();
            title = idea.title();
            pitch = idea.pitch();
        }
        Idea idea = repo.insertIdea(t, new NewIdea(board.id(), title, pitch, "seed", sourceUrl, num(b.path("x")), num(b.path("y")), 0));
        emitIdeas(board.id(), List.of(idea));
        queueJudge(t, List.of(idea.id()));
        return Js.obj("idea", idea);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> expand(String t, long ideaId) {
        Map<String, Object> out = doExpand(t, ideaId);
        queueJudge(t, ((List<Idea>) out.get("ideas")).stream().map(Idea::id).toList());
        return out;
    }

    public Map<String, Object> combine(String t, long boardId, Long aId, Long bId) {
        Map<String, Object> out = doCombine(t, boardId, aId, bId);
        queueJudge(t, List.of(((Idea) out.get("idea")).id()));
        return out;
    }

    public Map<String, Object> createProject(String t, long ideaId) {
        return createProject(t, mustIdea(t, ideaId));
    }

    public Map<String, Object> reveal(String t, long ideaId) {
        Idea idea = mustIdea(t, ideaId);
        if (settings(mustBoard(t, idea.boardId())).shared()) throw new AppError(403, "not_available", "Not available on a shared server");
        if (idea.projectPath() == null || idea.projectPath().isEmpty() || !Files.exists(Path.of(idea.projectPath()))) {
            throw new AppError(404, "no_project", "The project folder is gone");
        }
        io.reveal().accept(idea.projectPath());
        return Js.obj("ok", true);
    }

    /** The idea's project folder, for the zip download; on a shared server only inside the tenant's projects folder. */
    public Path projectDir(String t, long ideaId) {
        Idea idea = mustIdea(t, ideaId);
        AppSettings s = settings(mustBoard(t, idea.boardId()));
        Path folder = projectFolder(s, idea);
        if (folder == null || !Files.isDirectory(folder)) throw new AppError(404, "no_project", "This idea has no project folder");
        try {
            Path dir = folder.toRealPath();
            if (s.shared() && !dir.startsWith(Path.of(s.projectsRoot()).toRealPath())) throw new AppError(404, "no_project", "This idea has no project folder");
            return dir;
        } catch (IOException e) {
            throw new AppError(404, "no_project", "This idea has no project folder");
        }
    }

    public Map<String, Object> setMedal(String t, long ideaId, JsonNode m) {
        mustIdea(t, ideaId);
        boolean isNull = m == null || m.isNull();
        if (!isNull && !(Js.isInt(m) && m.asDouble() >= 0 && m.asDouble() <= 3)) {
            throw new AppError(400, "bad_request", "medalOverride is 0–3 or null");
        }
        Idea idea = repo.setMedalOverride(t, ideaId, isNull ? null : m.asInt());
        emitIdeas(idea.boardId(), List.of(idea));
        return Js.obj("idea", idea);
    }

    public Map<String, Object> deleteIdea(String t, long ideaId) {
        Idea idea = repo.getIdea(t, ideaId);
        boolean ok = repo.deleteIdea(t, ideaId);
        if (idea != null) bus.emit(idea.boardId(), event("removed", "ids", List.of(ideaId)));
        return Js.obj("ok", ok);
    }

    /** Announce an idea to other boards: each gets a guest copy that can cross-breed there. */
    public Map<String, Object> announce(String t, long ideaId, JsonNode boardIds) {
        Idea idea = mustIdea(t, ideaId);
        boolean isGuest = "guest".equals(idea.kind()) && idea.originIdeaId() != null && idea.originIdeaId() != 0;
        long originIdeaId = isGuest ? idea.originIdeaId() : idea.id();
        Long originBoardId = isGuest ? idea.originBoardId() : Long.valueOf(idea.boardId());
        Set<Long> wanted = new LinkedHashSet<>();
        if (boardIds != null && boardIds.isString() && boardIds.stringValue().equals("all")) {
            repo.listBoards(t).forEach(x -> wanted.add(x.get("id").asLong()));
        } else {
            for (JsonNode v : Js.arr(boardIds)) if (Js.isInt(v)) wanted.add(v.asLong());
        }
        List<Map<String, Object>> guests = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();
        for (long boardId : wanted) {
            if (boardId == idea.boardId()) continue;
            if (repo.getBoard(t, boardId) == null || repo.hasGuest(t, boardId, originIdeaId)) {
                skipped.add(boardId);
                continue;
            }
            double[] at = besideBoard(repo.listIdeas(t, boardId));
            Idea guest = repo.insertIdea(t, new NewIdea(boardId, idea.title(), idea.pitch(), "guest", idea.sourceUrl(), at[0], at[1], 0,
                    originIdeaId, originBoardId));
            guests.add(Js.obj("boardId", boardId, "idea", guest));
            emitIdeas(boardId, List.of(guest));
        }
        // Judged against each target board's own goal, not the one it came from.
        queueJudge(t, guests.stream().map(g -> ((Idea) g.get("idea")).id()).toList());
        return Js.obj("guests", guests, "skipped", skipped);
    }

    /** Spin an idea off into its own board, to refine it in its own context. */
    /** Send an idea to perso/ai-scientist as an ideation job: {job: {id, url, status}}. */
    public Map<String, Object> toScientist(String t, long ideaId) {
        Idea idea = mustIdea(t, ideaId);
        Board board = mustBoard(t, idea.boardId());
        AppSettings s = settings(board);
        String key = !s.scientistKey().isEmpty() || s.shared() ? s.scientistKey()
                : java.util.Objects.requireNonNullElse(Providers.env.apply("AI_SCIENTIST_API_KEY"), "");
        if (s.scientistUrl().isEmpty() || key.isEmpty()) {
            throw new AppError(400, "no_scientist", "Set the AI Scientist address and API key in the app settings");
        }
        if (s.shared()) {
            try {
                Providers.requirePublic(s.scientistUrl());
            } catch (ProviderError e) {
                throw new AppError(400, e.code, AppError.messageOf(e));
            }
        }
        return Js.obj("job", Scientist.submit(s.scientistUrl(), key, idea, board.goal(), s.shared()));
    }

    public Map<String, Object> spinoff(String t, long ideaId) {
        Idea idea = mustIdea(t, ideaId);
        Board parent = mustBoard(t, idea.boardId());
        String goal = Js.cut(idea.pitch() != null && !idea.pitch().isEmpty() ? idea.title() + ": " + idea.pitch() : idea.title(), 600);
        ObjectNode config = Js.tree(parent.config()).put("mode", "manual");
        Board board = repo.createBoard(t, Js.cut(idea.title(), 120), goal, null, config, idea.id());
        Idea root = repo.insertIdea(t, new NewIdea(board.id(), idea.title(), idea.pitch(), "seed", idea.sourceUrl(), 0, 0, 0,
                idea.id(), idea.boardId()));
        bus.emit(parent.id(), event("spinoff", "ideaId", idea.id(), "boardId", board.id(), "title", board.title()));
        queueJudge(t, List.of(root.id()));
        return Js.obj("board", repo.getBoard(t, board.id()), "idea", root);
    }

    public Map<String, Object> updateBoard(String t, long boardId, String title, String goal, JsonNode config) {
        mustBoard(t, boardId);
        Board board = repo.updateBoard(t, boardId, title, goal, config);
        bus.emit(boardId, event("board", "board", board));
        return Js.obj("board", board);
    }

    private static int fitnessOr(Idea i) {
        Integer f = Score.fitness(i);
        return f == null ? -1 : f;
    }

    /** One Elo duel between two of the board's best judged ideas: {a, b, scoreA, reasons, ideas}. */
    public Map<String, Object> duel(String t, long boardId) {
        Board board = mustBoard(t, boardId);
        Idea[] pair = Elo.pickPair(repo.listIdeas(t, board.id()), java.util.concurrent.ThreadLocalRandom.current());
        if (pair == null) throw new AppError(400, "too_few", "A duel needs at least two judged ideas");
        Idea a = pair[0], b = pair[1];
        Elo.Duel d = call(board, () -> Elo.duel(chat(board), modelFor(board, "judge"), board.goal(), a, b, board.config().judgeGuidance())).value();
        double[] r = Elo.update(Elo.rating(a), Elo.rating(b), d.scoreA());
        repo.saveDuel(t, a.id(), r[0], b.id(), r[1]);
        List<Idea> both = List.of(repo.getIdea(t, a.id()), repo.getIdea(t, b.id()));
        emitIdeas(board.id(), both);
        return Js.obj("a", a.id(), "b", b.id(), "scoreA", d.scoreA(), "reasons", d.reasons(), "ideas", both);
    }

    public Map<String, Object> summarize(String t, long boardId) {
        Board board = mustBoard(t, boardId);
        List<Llm.SummaryIdea> ideas = repo.listIdeas(t, board.id()).stream()
                .sorted(Comparator.comparingInt(Engine::fitnessOr).reversed())
                .map(i -> new Llm.SummaryIdea(i.title(), i.pitch(), i.kind(), Score.scoreSum(i), i.reason()))
                .toList();
        if (ideas.size() < 2) throw new AppError(400, "too_few", "A summary needs at least two ideas");
        String lang = board.config().lang();
        ObjectNode summary = call(board, () -> llm(board).summarize(modelFor(board, "summary"), board.goal(), lang, ideas)).value();
        summary.put("ideaCount", ideas.size()).put("lang", lang).put("createdAt", Js.isoNow());
        Board saved = repo.setSummary(t, board.id(), summary);
        bus.emit(board.id(), event("board", "board", saved));
        return Js.obj("board", saved);
    }

    /** The report's conclusions across every board, saved so the window opens instantly. */
    public Repo.Report synthesize(String t, int days, String lang) {
        Repo.Report report = repo.report(t, days);
        List<Repo.ReportBoard> boards = report.boards().stream().filter(b -> b.ideaCount() > 0).toList();
        if (boards.isEmpty()) throw new AppError(400, "too_few", "There are no ideas to report on yet");
        List<Llm.SynthesisBoard> input = boards.stream().map(b -> new Llm.SynthesisBoard(b.title(), b.goal(),
                b.summary() == null ? "" : Js.str(b.summary().path("headline")),
                b.best().stream().map(i -> new Llm.SynthesisIdea(i.title(), i.pitch(), i.sum())).toList(),
                b.projects().stream().map(Repo.ProjectRef::title).toList())).toList();
        AppSettings s = settings.apply(t);
        ObjectNode synthesis = call(t, 0, () -> new Llm(io.chat().apply(s)).synthesize(s.models().get("summary"), lang, days, input)).value();
        repo.saveSynthesis(t, days, lang, synthesis);
        return repo.report(t, days);
    }

    // ---- leads: each board's own researchers

    public Map<String, Object> refreshLeads(String t, long boardId, String source) {
        Board board = mustBoard(t, boardId);
        String lang = board.config().lang();
        Board.Researcher researcher = source.equals("x") ? board.config().researchers().x() : board.config().researchers().research();
        String focus = researcher.focus();
        String topic = Stream.of(board.goal(), focus).filter(s -> !s.isEmpty()).reduce((x, y) -> x + " — " + y).orElse("");
        Leads.Found r;
        if (source.equals("x")) {
            r = call(board, () -> Leads.xLeads(chat(board), modelFor(board, "x"), topic, lang)).value();
        } else {
            List<Leads.Paper> papers;
            try {
                papers = io.fetchPapers().apply(focus.isEmpty() ? board.goal() : focus);
            } catch (RuntimeException e) {
                throw new AppError(502, "research_failed", messageOf(e));
            }
            r = call(board, () -> Leads.researchLeads(chat(board), modelFor(board, "research"), topic, lang, papers)).value();
        }
        // Nothing verified: keep the old leads rather than wiping the panel.
        List<Lead> leads = !r.leads().isEmpty() ? repo.replaceLeads(t, board.id(), source, r.leads())
                : repo.listLeads(t, board.id()).stream().filter(l -> l.source().equals(source)).toList();
        bus.emit(board.id(), event("leads", "source", source, "leads", leads));
        return Js.obj("leads", leads, "dropped", r.dropped(), "empty", r.leads().isEmpty());
    }

    public Map<String, Object> seedLead(String t, long leadId, JsonNode at) {
        Lead lead = repo.getLead(t, leadId);
        if (lead == null) throw new AppError(404, "not_found", "Lead not found");
        double[] pos = Js.isFinite(at.path("x")) && Js.isFinite(at.path("y"))
                ? new double[]{at.path("x").asDouble(), at.path("y").asDouble()}
                : besideBoard(repo.listIdeas(t, lead.boardId()));
        String pitch = Js.cut(Stream.of(lead.summary(), lead.hook()).filter(s -> s != null && !s.isEmpty())
                .reduce((x, y) -> x + " " + y).orElse(""), 600);
        Idea idea = repo.insertIdea(t, new NewIdea(lead.boardId(), lead.title(), pitch, "seed", lead.url(), pos[0], pos[1], 0));
        Lead used = repo.markLeadUsed(t, lead.id(), idea.id());
        emitIdeas(lead.boardId(), List.of(idea));
        bus.emit(lead.boardId(), event("leads", "source", lead.source(),
                "leads", repo.listLeads(t, lead.boardId()).stream().filter(l -> l.source().equals(lead.source())).toList()));
        queueJudge(t, List.of(idea.id()));
        return Js.obj("idea", idea, "lead", used);
    }

    /** Judge in parallel; fail only if every judgement failed. */
    private void judgeAll(String t, List<Long> ids) {
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

    /**
     * One autopilot step: judge what is unjudged, otherwise breed (combine by
     * tournament selection) or mutate (expand a strong idea). Waits for its own
     * judgements so the next step sees the scores.
     */
    @SuppressWarnings("unchecked")
    public Step autoStep(String t, long boardId) {
        Board board = mustBoard(t, boardId);
        List<Idea> ideas = repo.listIdeas(t, boardId);
        if (ideas.isEmpty()) return new Step("idle", "empty", null);
        List<Long> unjudged = ideas.stream()
                .filter(i -> !Score.isJudged(i) && i.medalOverride() == null && !judging.contains(i.id()))
                .limit(3).map(Idea::id).toList();
        if (!unjudged.isEmpty()) {
            judgeAll(t, unjudged);
            return new Step("judge", null, unjudged);
        }
        Evolution.Move move = Evolution.nextMove(ideas, repo.listEdges(t, boardId), judging, board.config().diversity());
        return switch (move) {
            case null -> new Step("idle", "nothing_to_do", null);
            case Evolution.Expand e -> {
                List<Long> ids = ((List<Idea>) doExpand(t, e.idea().id()).get("ideas")).stream().map(Idea::id).toList();
                judgeAll(t, ids);
                yield new Step("expand", null, ids);
            }
            case Evolution.Combine c -> {
                long id = ((Idea) doCombine(t, boardId, c.a().id(), c.b().id()).get("idea")).id();
                judgeAll(t, List.of(id));
                yield new Step("combine", null, List.of(id));
            }
        };
    }
}
