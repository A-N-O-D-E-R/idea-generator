package bio.anode.ideagenerator.controller;

import static bio.anode.ideagenerator.controller.Requests.MARKDOWN;
import static bio.anode.ideagenerator.controller.Requests.TEXT_UTF8;
import static bio.anode.ideagenerator.controller.Requests.body;
import static bio.anode.ideagenerator.controller.Requests.lang;

import bio.anode.ideagenerator.Js;
import bio.anode.ideagenerator.model.Idea.Position;
import bio.anode.ideagenerator.service.Autopilot;
import bio.anode.ideagenerator.service.BoardService;
import bio.anode.ideagenerator.service.EventBus;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import tools.jackson.databind.JsonNode;

/** Boards, their live event stream and their Markdown export. */
@RestController
@RequestMapping("/api/boards")
@RequiredArgsConstructor
class BoardController {
    static final long STREAM_MS = 30 * 60_000L;

    private final BoardService boards;
    private final Autopilot autopilot;
    private final EventBus bus;
    private final CurrentTenant tenant;
    private final ScheduledExecutorService pinger = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("sse-ping").factory());

    @GetMapping
    Object list() {
        return Js.obj("boards", boards.list(tenant.id()));
    }

    @PostMapping
    Object create(@RequestBody(required = false) byte[] raw) {
        JsonNode b = body(raw);
        String lang = lang(b.path("lang"));
        String title = Js.str(b.path("title"), 120);
        return Js.obj("board", boards.create(tenant.id(), title.isEmpty() ? (lang.equals("en") ? "New board" : "Ny tavla") : title,
                Js.str(b.path("goal"), 600), Requests.longOrNull(b.path("templateId")), Js.object().put("lang", lang), null));
    }

    @GetMapping("/{id:\\d+}")
    Object get(@PathVariable long id) {
        return boards.view(tenant.id(), id, autopilot.status(id));
    }

    @PatchMapping("/{id:\\d+}")
    Object patch(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        JsonNode b = body(raw);
        JsonNode title = b.path("title"), goal = b.path("goal"), config = b.path("config");
        return boards.update(tenant.id(), id,
                title.isString() && !Js.trim(title.stringValue()).isEmpty() ? Js.str(title, 120) : null,
                goal.isString() ? Js.str(goal, 600) : null,
                config.isContainer() ? config : null);
    }

    @DeleteMapping("/{id:\\d+}")
    Object delete(@PathVariable long id) {
        return Js.obj("ok", boards.delete(tenant.id(), id));
    }

    @PutMapping("/{id:\\d+}/positions")
    Object positions(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        JsonNode b = body(raw);
        List<Position> positions = new ArrayList<>();
        for (JsonNode p : Js.arr(b.path("positions"))) {
            if (Js.isInt(p.path("id")) && Js.isFinite(p.path("x")) && Js.isFinite(p.path("y"))) {
                positions.add(new Position(p.path("id").asLong(),
                        Math.round(p.path("x").asDouble() * 10) / 10.0, Math.round(p.path("y").asDouble() * 10) / 10.0));
            }
        }
        return Js.obj("ok", true, "saved", boards.savePositions(tenant.id(), id, positions));
    }

    @GetMapping("/{id:\\d+}/export.md")
    ResponseEntity<String> export(@PathVariable long id, @RequestParam(required = false) String lang) {
        return ResponseEntity.ok().contentType(MARKDOWN).body(boards.exportMarkdown(tenant.id(), id, lang(lang)));
    }

    @PostMapping("/{id:\\d+}/duel")
    Object duel(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        body(raw);
        return boards.duel(tenant.id(), id);
    }

    @PostMapping("/{id:\\d+}/summary")
    Object summary(@PathVariable long id, @RequestBody(required = false) byte[] raw) {
        body(raw);
        return boards.summarize(tenant.id(), id);
    }

    /** Server-sent events: everything that happens on one board, live. */
    @GetMapping("/{id:\\d+}/events")
    ResponseEntity<?> events(@PathVariable long id) {
        if (!boards.exists(tenant.id(), id)) return ResponseEntity.status(404).body(Map.of("error", "Board not found"));
        // Ends after 30 min and the browser's EventSource reconnects: the ownership check and (oauth) the session are
        // checked again, so a stream does not outlive a logout or an expired session for long.
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(STREAM_MS);
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
        emitter.onTimeout(() -> {
            sub.cleanup();
            emitter.complete();
        });
        emitter.onError(t -> sub.cleanup());
        sub.write(EventBus.event("hello", "autopilot", autopilot.status(id)));
        sub.off = bus.on(id, sub::write);
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
}
