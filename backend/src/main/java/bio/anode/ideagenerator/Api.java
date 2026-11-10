package bio.anode.ideagenerator;

import bio.anode.ideagenerator.Providers.Provider;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * HTTP (upstream app.ts): routes onto the engine, live events (SSE) and the
 * Markdown exports. Bodies are read as raw JSON and coerced the way upstream
 * does, so the contract (including what bad input turns into) stays the same.
 */
@RestController
@RequestMapping("/api")
public class Api {
    private static final MediaType TEXT_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);
    private static final MediaType MARKDOWN = MediaType.parseMediaType("text/markdown; charset=utf-8");

    private final Repo repo;
    private final Engine engine;
    private final Autopilot autopilot;
    private final Settings settings;
    private final ScheduledExecutorService pinger = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("sse-ping").factory());

    public Api(Repo repo, Engine engine, Autopilot autopilot, Settings settings) {
        this.repo = repo;
        this.engine = engine;
        this.autopilot = autopilot;
        this.settings = settings;
    }

    static JsonNode body(byte[] raw) {
        if (raw == null || raw.length == 0) return Js.object();
        if (raw.length > 1_000_000) throw new AppError(413, "too_large", "Request body too large");
        try {
            JsonNode n = Js.M.readTree(raw);
            if (n == null || n.isMissingNode()) throw new AppError(400, "bad_json", "Invalid JSON");
            return n;
        } catch (JacksonException e) {
            throw new AppError(400, "bad_json", "Invalid JSON");
        }
    }

    /** Number(v) || 30, clamped to 1..365 (days query and body). */
    static int days(double v) {
        double d = Double.isNaN(v) || v == 0 ? 30 : v;
        return (int) Math.min(365, Math.max(1, d));
    }

    private static String lang(String v) {
        return "en".equals(v) ? "en" : "sv";
    }

    /** The signed-in tenant. */
    private String tenant() {
        return Repo.LOCAL;
    }

    private Board mustBoard(long id) {
        Board b = repo.getBoard(tenant(), id);
        if (b == null) throw new AppError(404, "not_found", "Board not found");
        return b;
    }

    private Map<String, Object> providerStatus() {
        Providers.AppSettings s = settings.get(tenant());
        List<Map<String, Object>> providers = Arrays.stream(Provider.values()).map(p -> Js.obj(
                "id", p.id(), "label", p.label, "local", p.local, "needsKey", p.needsKey, "search", p.search, "note", p.note,
                "keySource", Providers.keySource(s, p), "baseUrl", s.baseUrls().get(p.id()))).toList();
        return Js.obj("providers", providers,
                "spentToday", repo.spentOn(tenant(), null), "spentTotal", repo.spentTotal(tenant()));
    }

    // ---- app settings, per tenant: secrets go in, never out ({stored: true|false} instead)

    private Map<String, Object> settingsView() {
        Map<String, Object> out = providerStatus();
        out.put("settings", settings.view(tenant()));
        return out;
    }

    @GetMapping("/settings")
    Object getSettings() {
        return settingsView();
    }

    @PutMapping("/settings")
    Object putSettings(@RequestBody(required = false) byte[] raw) {
        JsonNode b = body(raw);
        if (!b.isObject()) throw new AppError(400, "bad_request", "Settings must be an object");
        settings.save(tenant(), b);
        return settingsView();
    }

    @GetMapping("/providers")
    Object providers() {
        return providerStatus();
    }

    @PostMapping("/providers/{id:[a-z]+}/models")
    Object models(@PathVariable String id, @RequestBody(required = false) byte[] raw) {
        body(raw);
        Provider p = Provider.of(id);
        if (p == null) throw new AppError(404, "not_found", "Unknown provider");
        try {
            return Js.obj("models", Providers.listModels(settings.get(tenant()), p));
        } catch (Providers.ProviderError e) {
            throw new AppError(502, e.code, AppError.messageOf(e));
        } catch (RuntimeException e) {
            throw new AppError(502, "provider_error", AppError.messageOf(e));
        }
    }

    // ---- boards

    @GetMapping("/boards")
    Object listBoards() {
        return Js.obj("boards", repo.listBoards(tenant()));
    }

    @PostMapping("/boards")
    Object createBoard(@RequestBody(required = false) byte[] raw) {
        JsonNode b = body(raw);
        String lang = lang(b.path("lang").isString() ? b.path("lang").stringValue() : null);
        String title = Js.str(b.path("title"), 120);
        Long templateId = Js.isInt(b.path("templateId")) ? b.path("templateId").asLong() : null;
        return Js.obj("board", repo.createBoard(tenant(), title.isEmpty() ? (lang.equals("en") ? "New board" : "Ny tavla") : title,
                Js.str(b.path("goal"), 600), templateId, Js.object().put("lang", lang), null));
    }

    @GetMapping("/boards/{id:\\d+}")
    Object getBoard(@PathVariable long id) {
        Board board = mustBoard(id);
        String t = tenant();
        List<Idea> ideas = repo.listIdeas(t, board.id());
        return Js.obj("board", board, "ideas", ideas, "edges", repo.listEdges(t, board.id()), "leads", repo.listLeads(t, board.id()),
                "spinoffs", repo.spinoffs(t, board.id()),
                "judging", ideas.stream().filter(i -> engine.judging.contains(i.id())).map(Idea::id).toList(),
                "autopilot", autopilot.status(board.id()), "spentToday", repo.spentOn(t, board.id()));
    }

    @PatchMapping("/boards/{id:\\d+}")
    Object patchBoard(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        JsonNode b = body(raw);
        JsonNode title = b.path("title"), goal = b.path("goal"), config = b.path("config");
        return engine.updateBoard(tenant(), id,
                title.isString() && !Js.trim(title.stringValue()).isEmpty() ? Js.str(title, 120) : null,
                goal.isString() ? Js.str(goal, 600) : null,
                config.isContainer() ? config : null);
    }

    @DeleteMapping("/boards/{id:\\d+}")
    Object deleteBoard(@PathVariable long id) {
        return Js.obj("ok", repo.deleteBoard(tenant(), id));
    }

    @PutMapping("/boards/{id:\\d+}/positions")
    Object positions(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        JsonNode b = body(raw);
        mustBoard(id);
        List<Repo.Position> positions = new ArrayList<>();
        for (JsonNode p : Js.arr(b.path("positions"))) {
            if (Js.isInt(p.path("id")) && Js.isFinite(p.path("x")) && Js.isFinite(p.path("y"))) {
                positions.add(new Repo.Position(p.path("id").asLong(),
                        Math.round(p.path("x").asDouble() * 10) / 10.0, Math.round(p.path("y").asDouble() * 10) / 10.0));
            }
        }
        repo.savePositions(tenant(), id, positions);
        return Js.obj("ok", true, "saved", positions.size());
    }

    @GetMapping("/boards/{id:\\d+}/export.md")
    ResponseEntity<String> exportBoard(@PathVariable long id, @RequestParam(required = false) String lang) {
        Board board = mustBoard(id);
        return ResponseEntity.ok().contentType(MARKDOWN)
                .body(Markdown.board(board, repo.listIdeas(tenant(), board.id()), repo.listEdges(tenant(), board.id()), lang(lang)));
    }

    @PostMapping("/boards/{id:\\d+}/duel")
    Object duel(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        body(raw);
        return engine.duel(tenant(), id);
    }

    @PostMapping("/boards/{id:\\d+}/summary")
    Object summary(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        body(raw);
        return engine.summarize(tenant(), id);
    }

    /** Server-sent events: everything that happens on one board, live. */
    @GetMapping("/boards/{id:\\d+}/events")
    ResponseEntity<?> events(@PathVariable long id) {
        if (repo.getBoard(tenant(), id) == null) return ResponseEntity.status(404).body(Map.of("error", "Board not found"));
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L); // 0 = never time out
        var sub = new Object() {
            volatile Runnable off;
            volatile ScheduledFuture<?> ping;

            void cleanup() {
                if (off != null) off.run();
                if (ping != null) ping.cancel(false);
            }

            void send(String text) {
                try {
                    emitter.send(text, TEXT_UTF8);
                } catch (IOException | IllegalStateException gone) {
                    cleanup();
                    throw new IllegalStateException("subscriber gone", gone);
                }
            }

            void write(Map<String, Object> e) {
                send("data: " + Js.stringify(e) + "\n\n");
            }
        };
        emitter.onCompletion(sub::cleanup);
        emitter.onTimeout(sub::cleanup);
        emitter.onError(t -> sub.cleanup());
        sub.write(Engine.event("hello", "autopilot", autopilot.status(id)));
        sub.off = engine.bus.on(id, sub::write);
        sub.ping = pinger.scheduleAtFixedRate(() -> {
            try {
                sub.send(": ping\n\n");
            } catch (RuntimeException ignored) {
                // already cleaned up
            }
        }, 25, 25, TimeUnit.SECONDS);
        return ResponseEntity.ok()
                .header("Content-Type", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("Connection", "keep-alive")
                .body(emitter);
    }

    // ---- ideas

    @PostMapping("/boards/{id:\\d+}/seed")
    Object seed(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        return engine.seed(tenant(), id, body(raw));
    }

    @PostMapping("/ideas/{id:\\d+}/expand")
    Object expand(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        body(raw);
        return engine.expand(tenant(), id);
    }

    private static Long intOrNull(JsonNode n) {
        return Js.isInt(n) ? n.asLong() : null;
    }

    @PostMapping("/boards/{id:\\d+}/combine")
    Object combine(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        JsonNode b = body(raw);
        return engine.combine(tenant(), id, intOrNull(b.path("a")), intOrNull(b.path("b")));
    }

    @PostMapping("/ideas/{id:\\d+}/judge")
    Object judge(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        body(raw);
        return engine.judge(tenant(), id);
    }

    @PatchMapping("/ideas/{id:\\d+}")
    Object patchIdea(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        JsonNode b = body(raw);
        if (!b.has("medalOverride")) throw new AppError(400, "bad_request", "Nothing to change");
        return engine.setMedal(tenant(), id, b.get("medalOverride"));
    }

    @DeleteMapping("/ideas/{id:\\d+}")
    Object deleteIdea(@PathVariable long id) {
        return engine.deleteIdea(tenant(), id);
    }

    @PostMapping("/ideas/{id:\\d+}/project")
    Object project(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        body(raw);
        return engine.createProject(tenant(), id);
    }

    @PostMapping("/ideas/{id:\\d+}/reveal")
    Object reveal(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        body(raw);
        return engine.reveal(tenant(), id);
    }

    @PostMapping("/ideas/{id:\\d+}/announce")
    Object announce(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        return engine.announce(tenant(), id, body(raw).path("boardIds"));
    }

    @PostMapping("/ideas/{id:\\d+}/scientist")
    Object scientist(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        body(raw);
        return engine.toScientist(tenant(), id);
    }

    @PostMapping("/ideas/{id:\\d+}/spinoff")
    Object spinoff(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        body(raw);
        return engine.spinoff(tenant(), id);
    }

    // ---- leads

    @PostMapping("/boards/{id:\\d+}/leads/{source:x|research}")
    Object refreshLeads(@PathVariable long id, @PathVariable String source, @RequestBody(required = false) byte[] raw) {
        body(raw);
        return engine.refreshLeads(tenant(), id, source);
    }

    @PostMapping("/leads/{id:\\d+}/seed")
    Object seedLead(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        return engine.seedLead(tenant(), id, body(raw));
    }

    @DeleteMapping("/leads/{id:\\d+}")
    Object deleteLead(@PathVariable long id) {
        return Js.obj("ok", repo.deleteLead(tenant(), id));
    }

    // ---- dashboard: keyword trends across all boards

    @GetMapping("/dashboard")
    Object dashboard(@RequestParam(required = false) String days) {
        return repo.dashboard(tenant(), days(days == null ? 0 : Js.num(days)));
    }

    @GetMapping("/keywords/{kw}/ideas")
    Object keywordIdeas(@PathVariable String kw) {
        return Js.obj("ideas", repo.ideasWithKeyword(tenant(), kw));
    }

    // ---- report: insights, ideas and projects across all boards

    @GetMapping("/report")
    Object report(@RequestParam(required = false) String days) {
        return repo.report(tenant(), days(days == null ? 0 : Js.num(days)));
    }

    @PostMapping("/report/synthesis")
    Object synthesis(@RequestBody(required = false) byte[] raw) {
        JsonNode b = body(raw);
        return engine.synthesize(tenant(), days(Js.num(b.path("days"))), lang(b.path("lang").isString() ? b.path("lang").stringValue() : null));
    }

    @GetMapping("/report.md")
    ResponseEntity<String> reportMd(@RequestParam(required = false) String days, @RequestParam(required = false) String lang) {
        return ResponseEntity.ok().contentType(MARKDOWN)
                .body(Markdown.report(repo.report(tenant(), days(days == null ? 0 : Js.num(days))), lang(lang)));
    }

    // ---- flow templates

    private record TemplateBody(String name, String system, String expand, String combine) {}

    private static TemplateBody templateBody(JsonNode b) {
        TemplateBody t = new TemplateBody(Js.str(b.path("name"), 80), Js.str(b.path("system"), 4000), Js.str(b.path("expand"), 4000),
                Js.str(b.path("combine"), 4000));
        if (t.name().isEmpty() || t.system().isEmpty() || t.expand().isEmpty() || t.combine().isEmpty()) {
            throw new AppError(400, "bad_request", "A template needs a name and all three prompts");
        }
        return t;
    }

    @GetMapping("/templates")
    Object templates() {
        return Js.obj("templates", repo.listTemplates(tenant()));
    }

    @PostMapping("/templates")
    Object createTemplate(@RequestBody(required = false) byte[] raw) {
        TemplateBody t = templateBody(body(raw));
        return Js.obj("template", repo.createTemplate(tenant(), t.name(), t.system(), t.expand(), t.combine()));
    }

    @PutMapping("/templates/{id:\\d+}")
    Object updateTemplate(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        TemplateBody t = templateBody(body(raw));
        Board.Template updated = repo.updateTemplate(tenant(), id, t.name(), t.system(), t.expand(), t.combine());
        if (updated == null) throw new AppError(404, "not_found", "Template not found");
        return Js.obj("template", updated);
    }

    @DeleteMapping("/templates/{id:\\d+}")
    Object deleteTemplate(@PathVariable long id) {
        if (repo.findTemplate(tenant(), id) == null) throw new AppError(404, "not_found", "Template not found");
        if (!repo.deleteTemplate(tenant(), id)) throw new AppError(400, "last_template", "The last template cannot be deleted");
        return Js.obj("ok", true);
    }

    /** Anything else under /api. */
    @RequestMapping("/**")
    Object noRoute(HttpServletRequest req) {
        throw new AppError(404, "not_found", "No route for " + req.getMethod() + " " + req.getRequestURI());
    }

    /** Errors as {error, code}, like upstream; static-file errors as {error}. */
    @RestControllerAdvice
    static class Errors {
        private static final Logger log = LoggerFactory.getLogger(Api.class);

        @ExceptionHandler(NoResourceFoundException.class)
        ResponseEntity<Object> notFound(HttpServletRequest req) {
            // Outside /api only GET and HEAD exist, as upstream.
            if (!req.getMethod().equals("GET") && !req.getMethod().equals("HEAD")) return method();
            return ResponseEntity.status(404).body(Map.of("error", "Not found"));
        }

        @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
        ResponseEntity<Object> method() {
            return ResponseEntity.status(405).body(Map.of("error", "Method not allowed"));
        }

        @ExceptionHandler(AsyncRequestNotUsableException.class)
        void gone() {
            // the client of an event stream went away
        }

        @ExceptionHandler(Exception.class)
        ResponseEntity<Object> error(Exception e, HttpServletRequest req) {
            int status = e instanceof AppError a ? a.status : 500;
            if (e instanceof AppError && status >= 500) log.error("[{} {}] {}", req.getMethod(), req.getRequestURI(), e.getMessage());
            else if (!(e instanceof AppError)) log.error("[{} {}] {}", req.getMethod(), req.getRequestURI(), e.getMessage(), e);
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                    .body(Js.obj("error", e.getMessage() != null ? e.getMessage() : "Server error",
                            "code", AppError.codeOf(e, "server_error")));
        }
    }
}
