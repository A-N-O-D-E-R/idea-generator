package bio.anode.ideagenerator;

import bio.anode.ideagenerator.model.Idea;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class EloTest {
    static Idea idea(long id, Integer sum, int duels) {
        Integer o = sum == null ? null : sum / 3, f = sum == null ? null : sum / 3, i = sum == null ? null : sum - 2 * (sum / 3);
        return new Idea(id, 1, "Idea " + id, "", "expand", null, 0, 0, o, f, i, null, null, null, 1, null, null, null, null,
                List.of(), "", null, duels);
    }

    @Test
    void ratingsMoveByKAtEqualStrengthAndSumIsKept() {
        assertEquals(0.5, Elo.expected(1000, 1000), 1e-12);
        double[] r = Elo.update(1000, 1000, 1);
        assertArrayEquals(new double[]{1016, 984}, r, 1e-9);
        double[] d = Elo.update(1200, 1000, 0.5);
        assertTrue(d[0] < 1200 && d[1] > 1000, "a draw against a weaker idea costs rating");
        assertEquals(2200, d[0] + d[1], 1e-9);
    }

    @Test
    void positionBiasBecomesADraw() {
        assertEquals(1, Elo.scoreFor("A", "A"));
        assertEquals(0, Elo.scoreFor("b", "A"));
        assertEquals(0.5, Elo.scoreFor("tie", "B"));
        assertEquals(0.5, Elo.scoreFor("", "B"), "no answer is a draw");
        var always = Elo.duel(r -> new Providers.ChatResult(Js.parse("{\"winner\":\"A\",\"reason\":\"first\"}"), 0.01),
                "m", "goal", idea(1, 9, 0), idea(2, 9, 0), "");
        assertEquals(0.5, always.value().scoreA(), "a judge that always picks A gives a draw");
        assertEquals(0.02, always.costUsd(), 1e-12);
        assertEquals(List.of("first", "first"), always.value().reasons());
    }

    @Test
    void pairsComeFromTheTopAndFavourFewDuels() {
        assertNull(Elo.pickPair(List.of(idea(1, 9, 0), idea(2, null, 0)), new Random(1)), "unjudged ideas don't duel");
        List<Idea> ideas = new java.util.ArrayList<>();
        for (int k = 1; k <= 10; k++) ideas.add(idea(k, 3 + k, k <= 8 ? 5 : 0)); // ideas 9 and 10 are best and fresh
        ideas.add(idea(11, 3, 0)); // fresh but outside the top 8
        Idea[] p = Elo.pickPair(ideas, new Random(7));
        assertEquals(java.util.Set.of(9L, 10L), java.util.Set.of(p[0].id(), p[1].id()));
    }
}
