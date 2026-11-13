package bio.anode.ideagenerator;

import bio.anode.ideagenerator.model.Idea;

import bio.anode.ideagenerator.model.Idea.Edge;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;

/**
 * Port of public/js/evolve.js: decides the next autopilot move. Parents are
 * picked by tournament selection on the judge's score; now and then it
 * mutates instead (expands a strong idea not expanded yet).
 */
public final class Evolution {
    private Evolution() {}

    public sealed interface Move permits Expand, Combine {}

    public record Expand(Idea idea) implements Move {}

    public record Combine(Idea a, Idea b) implements Move {}

    public record Stats(int count, int generation, Double first, Double latest, Integer best) {}

    public static String pairKey(long a, long b) {
        return a < b ? a + "-" + b : b + "-" + a;
    }

    /** Ideas that can breed: judged or hand-medalled. */
    public static List<Idea> population(List<Idea> ideas) {
        return ideas.stream().filter(i -> Score.fitness(i) != null).toList();
    }

    private static Map<Long, String> kinds(List<Idea> ideas) {
        Map<Long, String> m = new HashMap<>();
        for (Idea i : ideas) m.put(i.id(), i.kind());
        return m;
    }

    /** Pairs that must not be bred again: already combined, or parent and child. */
    public static Set<String> usedPairs(List<Idea> ideas, List<Edge> edges) {
        Map<Long, String> kindOf = kinds(ideas);
        Map<Long, List<Long>> parentsOf = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (Edge e : edges) {
            used.add(pairKey(e.from(), e.to()));
            parentsOf.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e.from());
        }
        parentsOf.forEach((child, parents) -> {
            if ("combine".equals(kindOf.get(child)) && parents.size() == 2) used.add(pairKey(parents.get(0), parents.get(1)));
        });
        return used;
    }

    /** Ideas that already have expand-children. */
    public static Set<Long> expandedIds(List<Idea> ideas, List<Edge> edges) {
        Map<Long, String> kindOf = kinds(ideas);
        Set<Long> out = new java.util.LinkedHashSet<>();
        for (Edge e : edges) if ("expand".equals(kindOf.get(e.to()))) out.add(e.from());
        return out;
    }

    private static Idea tournament(List<Idea> pool, int k, DoubleSupplier rng, Map<Long, Double> fit) {
        Idea best = null;
        for (int i = 0; i < k; i++) {
            Idea c = pool.get((int) Math.floor(rng.getAsDouble() * pool.size()));
            if (best == null || fit.get(c.id()) > fit.get(best.id())) best = c;
        }
        return best;
    }

    /** Niche radius for fitness sharing: keyword sets closer than this (1 - Jaccard) share their fitness. */
    public static final double SHARING_RADIUS = 0.7;

    /** 1 - Jaccard of the judge's keywords; an idea without keywords is far from everything. */
    public static double distance(Idea a, Idea b) {
        if (a.keywords().isEmpty() || b.keywords().isEmpty()) return a.id() == b.id() ? 0 : 1;
        Set<String> union = new HashSet<>(a.keywords());
        union.addAll(b.keywords());
        long both = a.keywords().stream().distinct().filter(b.keywords()::contains).count();
        return 1 - (double) both / union.size();
    }

    /**
     * Fitness sharing (Goldberg & Richardson): an idea's fitness divided by how
     * crowded its niche is, sum of (1 - d/radius) over ideas within the radius,
     * itself included. A big family of near-copies breeds less, a lone niche
     * more. radius 0 = plain fitness (upstream behaviour).
     */
    public static Map<Long, Double> sharedFitness(List<Idea> pool, double radius) {
        Map<Long, Double> out = new HashMap<>();
        for (Idea i : pool) {
            double niche = 0;
            if (radius > 0) for (Idea j : pool) {
                double d = distance(i, j);
                if (d < radius) niche += 1 - d / radius;
            }
            out.put(i.id(), Score.fitness(i) / Math.max(1, niche));
        }
        return out;
    }

    public static Move nextMove(List<Idea> ideas, List<Edge> edges, Set<Long> busy) {
        return nextMove(ideas, edges, busy, false);
    }

    public static Move nextMove(List<Idea> ideas, List<Edge> edges, Set<Long> busy, boolean diversity) {
        return nextMove(ideas, edges, Math::random, busy, Set.of(), 0.2, 3, diversity ? SHARING_RADIUS : 0);
    }

    public static Move nextMove(List<Idea> ideas, List<Edge> edges, DoubleSupplier rng, Set<Long> busy,
                                Set<String> inFlight, double mutationRate, int k) {
        return nextMove(ideas, edges, rng, busy, inFlight, mutationRate, k, 0);
    }

    /** @return a combine, an expand, or null when there is nothing to do. */
    public static Move nextMove(List<Idea> ideas, List<Edge> edges, DoubleSupplier rng, Set<Long> busy,
                                Set<String> inFlight, double mutationRate, int k, double sharingRadius) {
        List<Idea> pool = population(ideas).stream().filter(i -> !busy.contains(i.id())).toList();
        Map<Long, Double> fit = sharedFitness(pool, sharingRadius);
        Set<Long> expanded = expandedIds(ideas, edges);
        Set<String> used = usedPairs(ideas, edges);
        Idea bestUnexpanded = pool.stream()
                .sorted(Comparator.comparing((Idea i) -> fit.get(i.id())).reversed())
                .filter(i -> !expanded.contains(i.id()))
                .findFirst().orElse(null);

        if (pool.size() < 3 || rng.getAsDouble() < mutationRate) {
            if (bestUnexpanded != null) return new Expand(bestUnexpanded);
        }
        if (pool.size() >= 2) {
            for (int attempt = 0; attempt < 40; attempt++) {
                Idea a = tournament(pool, k, rng, fit);
                List<Idea> rest = pool.stream().filter(i -> i.id() != a.id()).toList();
                Idea b = tournament(rest, k, rng, fit);
                String key = pairKey(a.id(), b.id());
                if (used.contains(key) || inFlight.contains(key)) continue;
                return new Combine(a, b);
            }
        }
        return bestUnexpanded != null ? new Expand(bestUnexpanded) : null;
    }

    /**
     * Has quality moved? Mean fitness of the first and the latest `window` bred
     * ideas (seeds and guests excluded), plus the best so far.
     */
    public static Stats evolutionStats(List<Idea> ideas, int window) {
        List<Idea> bred = ideas.stream()
                .filter(i -> !"seed".equals(i.kind()) && !"guest".equals(i.kind()) && Score.fitness(i) != null)
                .sorted(Comparator.comparingLong(Idea::id))
                .toList();
        int generation = ideas.stream().mapToInt(Idea::generation).max().orElse(0);
        generation = Math.max(0, generation);
        return new Stats(
                bred.size(),
                generation,
                mean(bred.subList(0, Math.min(window, bred.size()))),
                bred.size() > window ? mean(bred.subList(bred.size() - window, bred.size())) : null,
                bred.isEmpty() ? null : bred.stream().mapToInt(Score::fitness).max().getAsInt());
    }

    public static Stats evolutionStats(List<Idea> ideas) {
        return evolutionStats(ideas, 8);
    }

    private static Double mean(List<Idea> xs) {
        return xs.isEmpty() ? null : xs.stream().mapToInt(Score::fitness).average().getAsDouble();
    }
}
