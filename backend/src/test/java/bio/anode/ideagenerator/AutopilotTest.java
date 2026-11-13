package bio.anode.ideagenerator;

import static bio.anode.ideagenerator.repository.TenantRepository.LOCAL;
import static bio.anode.ideagenerator.service.Autopilot.backoffMs;
import static bio.anode.ideagenerator.service.Autopilot.nextLocalMidnight;
import static bio.anode.ideagenerator.service.Autopilot.rateCheck;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bio.anode.ideagenerator.model.Board;
import bio.anode.ideagenerator.model.Idea;
import bio.anode.ideagenerator.model.NewIdea;
import bio.anode.ideagenerator.service.Ai;
import bio.anode.ideagenerator.service.Autopilot;
import bio.anode.ideagenerator.service.Autopilot.Decision;
import bio.anode.ideagenerator.service.Autopilot.RateInput;
import bio.anode.ideagenerator.service.EventBus;
import bio.anode.ideagenerator.service.EvolutionService;
import bio.anode.ideagenerator.Providers.AppSettings;
import bio.anode.ideagenerator.Providers.ChatResult;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class AutopilotTest {

    static final long T = LocalDateTime.parse("2026-11-02T12:00:00").atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

    static RateInput base() {
        return new RateInput(T, List.of(), 0, 10, 60, 0, 1, 0, 5, 0, 0);
    }

    static RateInput with(java.util.function.UnaryOperator<RateInputBuilder> f) {
        return f.apply(new RateInputBuilder()).build();
    }

    /** Small builder so each case reads like upstream's {...base, field: value}. */
    static final class RateInputBuilder {
        long now = T, lastAt = 0, lastErrorAt = 0;
        List<Long> steps = List.of();
        int paceSeconds = 10, stepsPerHour = 60, errors = 0;
        double boardSpent = 0, boardUsdPerDay = 1, globalSpent = 0, globalUsdPerDay = 5;

        RateInputBuilder globalSpent(double v) { globalSpent = v; return this; }
        RateInputBuilder boardSpent(double v) { boardSpent = v; return this; }
        RateInputBuilder boardUsdPerDay(double v) { boardUsdPerDay = v; return this; }
        RateInputBuilder errors(int v) { errors = v; return this; }
        RateInputBuilder lastErrorAt(long v) { lastErrorAt = v; return this; }
        RateInputBuilder steps(Long... v) { steps = List.of(v); return this; }
        RateInputBuilder stepsPerHour(int v) { stepsPerHour = v; return this; }
        RateInputBuilder lastAt(long v) { lastAt = v; return this; }

        RateInput build() {
            return new RateInput(now, steps, lastAt, paceSeconds, stepsPerHour, boardSpent, boardUsdPerDay, globalSpent, globalUsdPerDay, errors, lastErrorAt);
        }
    }

    @Test
    void rateLimiterBudgetsBackoffHourlyWindowPace() {
        assertEquals(Decision.OK, rateCheck(base()));
        assertEquals(new Decision(false, "global_budget", nextLocalMidnight(T)), rateCheck(with(b -> b.globalSpent(5))));
        assertEquals("board_budget", rateCheck(with(b -> b.boardSpent(1.2))).reason());
        assertTrue(rateCheck(with(b -> b.boardSpent(0.99))).ok());
        // 0 = free models only: nothing spent keeps running, the first paid cent stops it
        assertTrue(rateCheck(with(b -> b.boardUsdPerDay(0).boardSpent(0))).ok());
        assertEquals("board_budget", rateCheck(with(b -> b.boardUsdPerDay(0).boardSpent(0.001))).reason());

        assertEquals(List.of(30_000L, 60_000L, 120_000L, 900_000L), Stream.of(1, 2, 3, 10).map(Autopilot::backoffMs).toList());
        assertEquals("backoff", rateCheck(with(b -> b.errors(2).lastErrorAt(T - 30_000))).reason());
        assertTrue(rateCheck(with(b -> b.errors(2).lastErrorAt(T - 61_000))).ok());

        assertEquals(new Decision(false, "hourly", T - 3_500_000 + 3_600_000), rateCheck(with(b -> b.steps(T - 3_500_000, T - 1000).stepsPerHour(2))));
        assertTrue(rateCheck(with(b -> b.steps(T - 3_700_000, T - 1000).stepsPerHour(2))).ok(), "old steps leave the window");

        assertEquals(new Decision(false, "pace", T + 6000), rateCheck(with(b -> b.lastAt(T - 4000))));
    }

    @Test
    void localMidnightIsTheNextCalendarDayAt0000() {
        ZonedDateTime m = java.time.Instant.ofEpochMilli(nextLocalMidnight(T)).atZone(ZoneId.systemDefault());
        assertEquals(List.of(3, 0, 0), List.of(m.getDayOfMonth(), m.getHour(), m.getMinute()));
    }

    @Test
    void schedulerOnlyAutoBoardsRunAtTheirPaceWithinTheirLimits() {
        TestApp app = new TestApp();
        AtomicLong now = new AtomicLong(T);
        List<Long> steps = new ArrayList<>();
        AtomicBoolean fail = new AtomicBoolean();
        EventBus bus = new EventBus();
        Autopilot ap = new Autopilot(app.boards, app.spend, bus, (t, boardId) -> {
            if (fail.get()) throw new AppError(502, "no_key", "no key");
            steps.add(boardId);
            app.spend.add(LOCAL, boardId, 0.3);
            return new EvolutionService.Step("combine", null, null);
        }, t -> AppSettings.defaults(), now::get, Runnable::run);
        Board auto = app.board(LOCAL, "Auto", "{\"mode\":\"auto\",\"autopilotSeconds\":10,\"rate\":{\"stepsPerHour\":3,\"usdPerDay\":100}}");
        app.board(LOCAL, "Manual", null);

        ap.tick();
        assertEquals(List.of(auto.id()), steps, "the manual board never runs");
        now.addAndGet(5_000);
        ap.tick();
        assertEquals(1, steps.size());
        assertEquals("pace", ap.status(auto.id()).state());
        now.addAndGet(5_000);
        ap.tick();
        now.addAndGet(10_000);
        ap.tick();
        assertEquals(3, steps.size());
        now.addAndGet(10_000);
        ap.tick();
        assertEquals(3, steps.size(), "three steps per hour");
        assertEquals("hourly", ap.status(auto.id()).state());

        // Errors back off, and the status says why.
        now.addAndGet(3_600_000);
        fail.set(true);
        ap.tick();
        assertEquals("error", ap.status(auto.id()).state());
        assertEquals("no_key", ap.status(auto.id()).code());
        now.addAndGet(15_000);
        ap.tick();
        assertEquals("backoff", ap.status(auto.id()).state());
        fail.set(false);
        now.addAndGet(30_000);
        ap.tick();
        assertEquals(4, steps.size(), "recovers after the backoff");

        // Budget: the board has spent 4 x $0.30; a $1 cap stops it until midnight.
        app.boards.update(LOCAL, auto.id(), null, null, Js.parse("{\"rate\":{\"stepsPerHour\":3,\"usdPerDay\":1}}"));
        now.addAndGet(20_000);
        ap.tick();
        assertEquals("board_budget", ap.status(auto.id()).state());
        assertEquals(4, steps.size());

        app.boards.update(LOCAL, auto.id(), null, null, Js.parse("{\"mode\":\"manual\"}"));
        ap.tick();
        assertEquals("manual", ap.status(auto.id()).state());
    }

    @Test
    void autoStepWithTheRealEngineJudgesFirstGrowsThenBreeds() {
        TestApp app = new TestApp();
        AtomicInteger n = new AtomicInteger();
        Providers.Chat chat = r -> {
            int k = n.incrementAndGet();
            String value = switch (r.schemaName()) {
                case "expand" -> "{\"ideas\":[" + String.join(",", Stream.of(1, 2, 3)
                        .map(x -> "{\"title\":\"Idé " + k + "." + x + "\",\"pitch\":\"p\"}").toList()) + "]}";
                case "judgement" -> "{\"reason\":\"r\",\"originality\":" + (2 + k % 3) + ",\"feasibility\":3,\"impact\":3,\"keywords\":[\"k\"]}";
                default -> "{\"title\":\"Korsning " + k + "\",\"pitch\":\"p\"}";
            };
            return new ChatResult(Js.parse(value), 0);
        };
        EvolutionService engine = app.evolution(new Ai.Io(s -> chat, u -> null, g -> List.of(), p -> {}, false, q -> List.of()));
        Board board = app.board(LOCAL, "B", null);
        assertEquals(new EvolutionService.Step("idle", "empty", null), engine.step(LOCAL, board.id()));
        app.ideas.insert(LOCAL, new NewIdea(board.id(), "Frö", "", "seed", null, 0, 0, 0));

        List<String> did = new ArrayList<>();
        for (int i = 0; i < 6; i++) did.add(engine.step(LOCAL, board.id()).did());
        assertEquals("judge", did.get(0), "an unjudged seed is judged first");
        assertEquals("expand", did.get(1), "a pool under three grows by expanding");
        assertTrue(did.contains("combine"), "breeds once the pool is big enough: " + did);
        List<Idea> ideas = app.ideas.list(LOCAL, board.id());
        assertTrue(ideas.stream().allMatch(i -> i.judgedAt() != null), "every step leaves its ideas judged");
        assertTrue(ideas.stream().anyMatch(i -> i.kind().equals("combine") && i.generation() >= 2));
    }

    @Test
    void eachTenantHasItsOwnGlobalBudget() {
        TestApp app = new TestApp();
        app.tenants.ensure("rich", "", null);
        app.tenants.ensure("broke", "", null);
        String auto = "{\"mode\":\"auto\",\"rate\":{\"usdPerDay\":100}}";
        Board rich = app.board("rich", "R", auto);
        Board broke = app.board("broke", "B", auto);
        app.spend.add("broke", 0, 6); // over its $5 app-wide limit, on no board
        List<String> stepped = new ArrayList<>();
        Autopilot ap = new Autopilot(app.boards, app.spend, new EventBus(), (t, boardId) -> {
            stepped.add(t + ":" + boardId);
            return new EvolutionService.Step("combine", null, null);
        }, t -> AppSettings.defaults(), () -> T, Runnable::run);
        ap.tick();
        assertEquals(List.of("rich:" + rich.id()), stepped, "a step runs as the board's own tenant");
        assertEquals("global_budget", ap.status(broke.id()).state());
    }
}
