package bio.anode.ideagenerator;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiversityTest {
    static Idea idea(long id, int sum, String... keywords) {
        int o = sum / 3, f = sum / 3, i = sum - 2 * (sum / 3);
        return new Idea(id, 1, "Idea " + id, "", "expand", null, 0, 0, o, f, i, null, null, null, 1, null, null, null, null,
                List.of(keywords), "", null, 0);
    }

    @Test
    void distanceIsOneMinusJaccard() {
        assertEquals(0, Evolution.distance(idea(1, 9, "a", "b"), idea(2, 9, "b", "a")), 1e-12);
        assertEquals(1 - 1.0 / 3, Evolution.distance(idea(1, 9, "a", "b"), idea(2, 9, "b", "c")), 1e-12);
        assertEquals(1, Evolution.distance(idea(1, 9), idea(2, 9, "a")), "no keywords: far from everything");
        assertEquals(0, Evolution.distance(idea(1, 9), idea(1, 9)));
    }

    @Test
    void sharingDividesACrowdedFamilyAndLeavesALoneNicheAlone() {
        List<Idea> pool = List.of(idea(1, 12, "solar", "roof"), idea(2, 12, "solar", "roof"), idea(3, 12, "solar", "roof"),
                idea(4, 10, "sound", "park"));
        var plain = Evolution.sharedFitness(pool, 0);
        assertEquals(12.0, plain.get(1L));
        assertEquals(10.0, plain.get(4L));
        var shared = Evolution.sharedFitness(pool, Evolution.SHARING_RADIUS);
        assertEquals(4.0, shared.get(1L), 1e-12, "three identical keyword sets share 12 three ways");
        assertEquals(10.0, shared.get(4L), 1e-12);
    }

    @Test
    void withDiversityTheLoneNicheBreedsMoreOften() {
        List<Idea> ideas = new ArrayList<>();
        for (long k = 1; k <= 6; k++) ideas.add(idea(k, 12, "solar", "roof", "panel"));
        ideas.add(idea(7, 10, "sound", "park"));
        assertEquals(parentCount(ideas, 0), parentCount(ideas, 0), "deterministic with a seeded rng");
        int without = parentCount(ideas, 0), with = parentCount(ideas, Evolution.SHARING_RADIUS);
        assertTrue(with > 2 * without, "lone niche as parent: " + without + " without sharing, " + with + " with");
    }

    /** How often idea 7 is a parent over many independent first moves. */
    static int parentCount(List<Idea> ideas, double radius) {
        Random rng = new Random(42);
        int n = 0;
        for (int run = 0; run < 2000; run++) {
            var m = Evolution.nextMove(ideas, List.of(), rng::nextDouble, Set.of(), Set.of(), 0, 3, radius);
            if (m instanceof Evolution.Combine c && (c.a().id() == 7 || c.b().id() == 7)) n++;
        }
        return n;
    }
}
