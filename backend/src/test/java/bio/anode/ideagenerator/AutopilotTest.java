package bio.anode.ideagenerator;

import static bio.anode.ideagenerator.Autopilot.backoffMs;
import static bio.anode.ideagenerator.Autopilot.nextLocalMidnight;
import static bio.anode.ideagenerator.Autopilot.rateCheck;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bio.anode.ideagenerator.Autopilot.Decision;
import bio.anode.ideagenerator.Autopilot.RateInput;
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

    static Repo memoryRepo() {
        return TestDb.freshRepo();
    }

    @Test
    void schedulerOnlyAutoBoardsRunAtTheirPaceWithinTheirLimits() {
        Repo repo = memoryRepo();
        AtomicLong now = new AtomicLong(T);
        List<Long> steps = new ArrayList<>();
        AtomicBoolean fail = new AtomicBoolean();
        Engine.Bus bus = new Engine.Bus();
        Autopilot ap = new Autopilot(repo, bus, (t, boardId) -> {
            if (fail.get()) throw new AppError(502, "no_key", "no key");
            steps.add(boardId);
            repo.addSpend(Repo.LOCAL, boardId, 0.3);
            return new Engine.Step("combine", null, null);
        }, t -> AppSettings.defaults(), now::get, Runnable::run);
        Board auto = repo.createBoard(Repo.LOCAL, "Auto", Js.parse("{\"mode\":\"auto\",\"autopilotSeconds\":10,\"rate\":{\"stepsPerHour\":3,\"usdPerDay\":100}}"));
        repo.createBoard(Repo.LOCAL, "Manual", null);

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
        repo.updateBoard(Repo.LOCAL, auto.id(), null, null, Js.parse("{\"rate\":{\"stepsPerHour\":3,\"usdPerDay\":1}}"));
        now.addAndGet(20_000);
        ap.tick();
        assertEquals("board_budget", ap.status(auto.id()).state());
        assertEquals(4, steps.size());

        repo.updateBoard(Repo.LOCAL, auto.id(), null, null, Js.parse("{\"mode\":\"manual\"}"));
        ap.tick();
        assertEquals("manual", ap.status(auto.id()).state());
    }

    @Test
    void autoStepWithTheRealEngineJudgesFirstGrowsThenBreeds() {
        Repo repo = memoryRepo();
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
        Engine engine = new Engine(repo, t -> AppSettings.defaults(), new Engine.Io(s -> chat, u -> null, g -> List.of(), p -> {}, false, q -> List.of()));
        Board board = repo.createBoard(Repo.LOCAL, "B", null);
        assertEquals(new Engine.Step("idle", "empty", null), engine.autoStep(Repo.LOCAL, board.id()));
        repo.insertIdea(Repo.LOCAL, new Repo.NewIdea(board.id(), "Frö", "", "seed", null, 0, 0, 0));

        List<String> did = new ArrayList<>();
        for (int i = 0; i < 6; i++) did.add(engine.autoStep(Repo.LOCAL, board.id()).did());
        assertEquals("judge", did.get(0), "an unjudged seed is judged first");
        assertEquals("expand", did.get(1), "a pool under three grows by expanding");
        assertTrue(did.contains("combine"), "breeds once the pool is big enough: " + did);
        List<Idea> ideas = repo.listIdeas(Repo.LOCAL, board.id());
        assertTrue(ideas.stream().allMatch(i -> i.judgedAt() != null), "every step leaves its ideas judged");
        assertTrue(ideas.stream().anyMatch(i -> i.kind().equals("combine") && i.generation() >= 2));
    }

    @Test
    void eachTenantHasItsOwnGlobalBudget() {
        Repo repo = memoryRepo();
        repo.ensureTenant("rich", "", null);
        repo.ensureTenant("broke", "", null);
        String auto = "{\"mode\":\"auto\",\"rate\":{\"usdPerDay\":100}}";
        Board rich = repo.createBoard("rich", "R", Js.parse(auto));
        Board broke = repo.createBoard("broke", "B", Js.parse(auto));
        repo.addSpend("broke", 0, 6); // over its $5 app-wide limit, on no board
        List<String> stepped = new ArrayList<>();
        Autopilot ap = new Autopilot(repo, new Engine.Bus(), (t, boardId) -> {
            stepped.add(t + ":" + boardId);
            return new Engine.Step("combine", null, null);
        }, t -> AppSettings.defaults(), () -> T, Runnable::run);
        ap.tick();
        assertEquals(List.of("rich:" + rich.id()), stepped, "a step runs as the board's own tenant");
        assertEquals("global_budget", ap.status(broke.id()).state());
    }
}
