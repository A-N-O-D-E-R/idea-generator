package bio.anode.ideagenerator.service;

import static bio.anode.ideagenerator.AppError.codeOf;
import static bio.anode.ideagenerator.AppError.messageOf;

import bio.anode.ideagenerator.Providers.AppSettings;
import bio.anode.ideagenerator.model.Board;
import bio.anode.ideagenerator.repository.BoardRepository;
import bio.anode.ideagenerator.repository.SpendRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Server-side autopilot for boards in automatic mode, with a rate limiter
 * (upstream autopilot.ts).
 *
 * Limits, checked in this order before every step:
 *   1. global dollars per local day (the tenant's app setting, across the tenant's boards)
 *   2. the board's dollars per local day
 *   3. error backoff (30 s, doubling, max 15 min)
 *   4. the board's steps per hour (sliding window)
 *   5. the board's pace (seconds between steps)
 * Dollar limits only see providers that report cost. A limit of 0 means "free
 * models only": a board on a local model keeps running, the first paid call stops it.
 */
@Slf4j
@RequiredArgsConstructor
public class Autopilot {
    static final long HOUR = 3_600_000;

    public record RateInput(long now, List<Long> steps, long lastAt, int paceSeconds, int stepsPerHour, double boardSpent,
                            double boardUsdPerDay, double globalSpent, double globalUsdPerDay, int errors, long lastErrorAt) {}

    /** ok, or why not (global_budget | board_budget | backoff | hourly | pace) and until when. */
    public record Decision(boolean ok, String reason, long until) {
        public static final Decision OK = new Decision(true, null, 0);
    }

    /** state: manual | running | idle | error | pace | hourly | backoff | board_budget | global_budget */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Status(String state, Long until, String code, String message, String last, Integer stepsLastHour, Double spentToday) {
        static final Status MANUAL = new Status("manual", null, null, null, null, null, null);
    }

    public static long nextLocalMidnight(long now) {
        ZoneId zone = ZoneId.systemDefault();
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    public static long backoffMs(int errors) {
        return errors > 0 ? Math.min(15 * 60_000L, 30_000L * (1L << Math.min(errors - 1, 20))) : 0;
    }

    private static boolean overBudget(double spent, double limit) {
        return spent > 0 && spent >= limit;
    }

    /** Pure: may this board take a step now? */
    public static Decision rateCheck(RateInput i) {
        if (overBudget(i.globalSpent(), i.globalUsdPerDay())) return new Decision(false, "global_budget", nextLocalMidnight(i.now()));
        if (overBudget(i.boardSpent(), i.boardUsdPerDay())) return new Decision(false, "board_budget", nextLocalMidnight(i.now()));
        if (i.errors() > 0 && i.now() < i.lastErrorAt() + backoffMs(i.errors())) {
            return new Decision(false, "backoff", i.lastErrorAt() + backoffMs(i.errors()));
        }
        List<Long> recent = i.steps().stream().filter(t -> t > i.now() - HOUR).toList();
        if (recent.size() >= i.stepsPerHour()) {
            return new Decision(false, "hourly", recent.stream().mapToLong(Long::longValue).min().orElse(i.now()) + HOUR);
        }
        if (i.now() < i.lastAt() + i.paceSeconds() * 1000L) return new Decision(false, "pace", i.lastAt() + i.paceSeconds() * 1000L);
        return Decision.OK;
    }

    private static final class BoardState {
        volatile boolean busy;
        List<Long> steps = new ArrayList<>();
        long lastAt, lastErrorAt;
        int errors;
        volatile Status status = new Status("idle", null, null, null, null, null, null);
        Status sent;
    }

    private final BoardRepository boards;
    private final SpendRepository spend;
    private final EventBus bus;
    /** One step on a board: (tenant, boardId) -> what it did. */
    public interface StepFn {
        EvolutionService.Step step(String tenant, long boardId);
    }

    private final StepFn autoStep;
    private final Function<String, AppSettings> settings;
    private final LongSupplier now;
    /** Where a step runs; tests pass Runnable::run to step synchronously. */
    private final Executor steps;
    private final Map<Long, BoardState> states = new ConcurrentHashMap<>();
    private ScheduledExecutorService timer;

    private void publish(long boardId, BoardState s, Status status) {
        synchronized (s) {
            s.status = status;
            if (status.equals(s.sent)) return; // do not repeat the same status every tick
            s.sent = status;
        }
        bus.emit(boardId, EventBus.event("autopilot", "status", status));
    }

    public synchronized void tick() {
        long t = now.getAsLong();
        List<Board> auto = boards.auto();
        Set<Long> autoIds = auto.stream().map(Board::id).collect(Collectors.toSet());
        for (Long id : List.copyOf(states.keySet())) {
            if (!autoIds.contains(id) && states.remove(id) != null) bus.emit(id, EventBus.event("autopilot", "status", Status.MANUAL));
        }
        Map<String, Double> globalSpentOf = new HashMap<>();
        for (Board b : auto) {
            long id = b.id();
            String tenant = b.tenantId();
            double globalSpent = globalSpentOf.computeIfAbsent(tenant, k -> spend.today(k, null));
            BoardState s = states.computeIfAbsent(id, k -> new BoardState());
            if (s.busy) continue;
            s.steps = new ArrayList<>(s.steps.stream().filter(x -> x > t - HOUR).toList());
            double boardSpent = spend.today(tenant, id);
            int stepsLastHour = s.steps.size();
            double spentToday = Math.round(boardSpent * 10000) / 10000.0;
            Decision d = rateCheck(new RateInput(t, s.steps, s.lastAt, b.config().autopilotSeconds(), b.config().rate().stepsPerHour(),
                    boardSpent, b.config().rate().usdPerDay(), globalSpent, settings.apply(tenant).maxUsdPerDay(), s.errors, s.lastErrorAt));
            if (!d.ok()) {
                // Keep the last result visible while merely pacing.
                if (d.reason().equals("pace") && (s.status.state().equals("idle") || s.status.state().equals("error"))) continue;
                publish(id, s, new Status(d.reason(), d.until(), null, null, s.status.last(), stepsLastHour, spentToday));
                continue;
            }
            s.busy = true;
            publish(id, s, new Status("running", null, null, null, s.status.last(), stepsLastHour, spentToday));
            int pace = b.config().autopilotSeconds();
            steps.execute(() -> {
                try {
                    EvolutionService.Step r = autoStep.step(tenant, id);
                    synchronized (s) {
                        if (!r.did().equals("idle")) s.steps.add(now.getAsLong());
                        s.lastAt = now.getAsLong();
                        s.errors = 0;
                    }
                    boolean idle = r.did().equals("idle");
                    publish(id, s, new Status(idle ? "idle" : "pace", now.getAsLong() + pace * 1000L, null, null,
                            idle ? r.reason() : r.did(), s.steps.size(), spentToday));
                } catch (RuntimeException e) {
                    synchronized (s) {
                        s.errors++;
                        s.lastErrorAt = s.lastAt = now.getAsLong();
                    }
                    publish(id, s, new Status("error", now.getAsLong() + backoffMs(s.errors), codeOf(e, "error"), messageOf(e),
                            null, stepsLastHour, spentToday));
                } finally {
                    s.busy = false;
                }
            });
        }
    }

    public synchronized Autopilot start(long tickMs) {
        if (timer == null) {
            timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("autopilot").factory());
            timer.scheduleAtFixedRate(() -> {
                try {
                    tick();
                } catch (RuntimeException e) {
                    log.error("[autopilot] {}", e.getMessage(), e);
                }
            }, tickMs, tickMs, TimeUnit.MILLISECONDS);
        }
        return this;
    }

    public synchronized void stop() {
        if (timer != null) timer.shutdownNow();
        timer = null;
    }

    public Status status(long boardId) {
        BoardState s = states.get(boardId);
        return s != null ? s.status : Status.MANUAL;
    }
}
