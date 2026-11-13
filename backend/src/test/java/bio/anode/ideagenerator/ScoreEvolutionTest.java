package bio.anode.ideagenerator;

import bio.anode.ideagenerator.model.Idea;

import static bio.anode.ideagenerator.Evolution.pairKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bio.anode.ideagenerator.Evolution.Combine;
import bio.anode.ideagenerator.Evolution.Expand;
import bio.anode.ideagenerator.Evolution.Move;
import bio.anode.ideagenerator.model.Idea.Edge;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ScoreEvolutionTest {

    /** An idea whose three scores add up to sum (null = unjudged). */
    static Idea idea(long id, Integer sum, String kind, int generation, Integer medalOverride) {
        Integer o = null, f = null, i = null;
        if (sum != null) {
            o = Math.min(5, sum - 2);
            f = Math.min(5, sum - o - 1);
            i = sum - o - f;
        }
        return new Idea(id, 1, "Idea " + id, "", kind, null, 0, 0, o, f, i, null, null, medalOverride, generation, null, null, null, null,
                List.of(), "", null, 0);
    }

    static Idea idea(long id, Integer sum) {
        return idea(id, sum, "expand", 1, null);
    }

    /** Deterministic PRNG (mulberry32), the same as upstream's tests. */
    static DoubleSupplier rngFrom(int start) {
        int[] seed = {start};
        return () -> {
            seed[0] = seed[0] + 0x6d2b79f5;
            int s = seed[0];
            int t = (s ^ (s >>> 15)) * (1 | s);
            t = (t + (t ^ (t >>> 7)) * (61 | t)) ^ t;
            return Integer.toUnsignedLong(t ^ (t >>> 14)) / 4294967296.0;
        };
    }

    @Test
    void medalThresholdsOnTheSum() {
        assertNull(Score.medalFromSum(null));
        assertEquals(List.of(0, 0, 1, 1, 2, 2, 3, 3), Stream.of(3, 8, 9, 10, 11, 12, 13, 15).map(Score::medalFromSum).toList());
    }

    @Test
    void scoresAreClampedToWholeNumbers1to5() {
        assertEquals(List.of(1, 1, 3, 5, 5, 1, 1),
                Arrays.asList(0, 1, 2.6, 5, 9, "x", null).stream().map(Score::clampScore).toList());
    }

    @Test
    void theHelperBuildsTheSumItWasAskedFor() {
        for (int s = 3; s <= 15; s++) assertEquals(s, Score.scoreSum(idea(1, s)));
    }

    @Test
    void aHandSetMedalBeatsTheJudge() {
        Idea i = idea(1, 6, "expand", 1, 3);
        assertEquals(3, Score.medal(i));
        assertEquals(14, Score.fitness(i));
        assertNull(Score.medal(idea(2, null)));
        assertNull(Score.fitness(idea(2, null)));
    }

    @Test
    void usedPairsAreNotBredAgain() {
        List<Idea> ideas = List.of(idea(1, 9), idea(2, 9), idea(3, 12, "combine", 1, null));
        List<Edge> edges = List.of(new Edge(1, 3), new Edge(2, 3));
        Set<String> used = Evolution.usedPairs(ideas, edges);
        assertTrue(used.contains(pairKey(1, 2)), "parents of a combination");
        assertTrue(used.contains(pairKey(1, 3)), "parent and child");
        for (int s = 0; s < 200; s++) {
            Move m = Evolution.nextMove(ideas, edges, rngFrom(s), Set.of(), Set.of(), 0, 3);
            if (m instanceof Combine c) assertTrue(!used.contains(pairKey(c.a().id(), c.b().id())), "bred a used pair");
        }
    }

    @Test
    void neverSelfCombinesAndSkipsBusyIdeasAndInFlightPairs() {
        List<Idea> ideas = List.of(idea(1, 10), idea(2, 11), idea(3, 12), idea(4, 13));
        for (int s = 0; s < 300; s++) {
            Move m = Evolution.nextMove(ideas, List.of(), rngFrom(s), Set.of(4L), Set.of(pairKey(2, 3)), 0, 3);
            Combine c = assertInstanceOf(Combine.class, m);
            assertNotEquals(c.a().id(), c.b().id());
            assertTrue(c.a().id() != 4 && c.b().id() != 4, "used a busy idea");
            assertNotEquals(pairKey(2, 3), pairKey(c.a().id(), c.b().id()));
        }
    }

    @Test
    void selectionFavoursFitterParents() {
        List<Idea> ideas = IntStream.range(0, 10).mapToObj(n -> idea(n + 1, 4 + n)).toList(); // sums 4..13
        Map<Long, Integer> count = new HashMap<>();
        DoubleSupplier rng = rngFrom(42);
        for (int n = 0; n < 4000; n++) {
            Combine c = (Combine) Evolution.nextMove(ideas, List.of(), rng, Set.of(), Set.of(), 0, 3);
            for (Idea p : List.of(c.a(), c.b())) count.merge(p.id(), 1, Integer::sum);
        }
        int best = count.getOrDefault(10L, 0), worst = count.getOrDefault(1L, 0);
        assertTrue(best > worst * 5, "best " + best + " vs worst " + worst);
    }

    @Test
    void aSmallPopulationGrowsByExpandingItsBestUnexpandedIdea() {
        List<Idea> ideas = List.of(idea(1, 5, "seed", 1, null), idea(2, 9, "seed", 1, null));
        Move m = Evolution.nextMove(ideas, List.of(), rngFrom(1), Set.of(), Set.of(), 0.2, 3);
        assertEquals(2, assertInstanceOf(Expand.class, m).idea().id());
        List<Idea> ideas2 = new ArrayList<>(ideas);
        ideas2.addAll(List.of(idea(3, 7), idea(4, 7), idea(5, 7)));
        List<Edge> edges = Stream.of(3L, 4L, 5L).map(to -> new Edge(2, to)).toList();
        assertEquals(List.of(2L), List.copyOf(Evolution.expandedIds(ideas2, edges)));
    }

    @Test
    void nothingToDoOnAnEmptyBoard() {
        assertNull(Evolution.nextMove(List.of(), List.of(), Set.of()));
        assertNull(Evolution.nextMove(List.of(idea(1, null)), List.of(), Set.of()));
    }

    @Test
    void evolutionStatsLeaveSeedsOutAndCompareWindows() {
        List<Idea> ideas = new ArrayList<>();
        ideas.add(idea(1, 3, "seed", 0, null));
        for (int n = 0; n < 8; n++) ideas.add(idea(n + 2, 8));
        for (int n = 0; n < 8; n++) ideas.add(idea(n + 10, 12, "expand", 5, null));
        Evolution.Stats s = Evolution.evolutionStats(ideas);
        assertEquals(16, s.count());
        assertEquals(8.0, s.first());
        assertEquals(12.0, s.latest());
        assertEquals(12, s.best());
        assertEquals(5, s.generation());
        assertNull(Evolution.evolutionStats(ideas.subList(0, 5)).latest(), "no latest until there is more than one window");
    }
}
