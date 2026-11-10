package bio.anode.ideagenerator;

import bio.anode.ideagenerator.Providers.Chat;
import bio.anode.ideagenerator.Providers.ChatRequest;
import bio.anode.ideagenerator.Providers.ChatResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.random.RandomGenerator;
import tools.jackson.databind.JsonNode;

/**
 * Pairwise ranking for the top of a board. The 1–5 rubric saturates among
 * strong ideas (many 12s and 13s), so the best ones are also compared head to
 * head and rated with Elo, as in Google's AI co-scientist. Each duel asks the
 * judge twice with the order swapped, because LLM judges favour a position;
 * answers that disagree come out as a draw.
 */
public final class Elo {
    private Elo() {}

    public static final double START = 1000, K = 32;
    /** Only this many of the best judged ideas take part. */
    public static final int TOP = 8;

    public static double rating(Idea i) {
        return i.elo() == null ? START : i.elo();
    }

    /** Expected score of a against b. */
    public static double expected(double a, double b) {
        return 1 / (1 + Math.pow(10, (b - a) / 400));
    }

    /** New ratings of a and b after a scored scoreA (1 win, 0.5 draw, 0 loss). */
    public static double[] update(double a, double b, double scoreA) {
        double e = expected(a, b);
        return new double[]{a + K * (scoreA - e), b + K * ((1 - scoreA) - (1 - e))};
    }

    /** The two of the top ideas with the fewest duels (ties broken at random), or null if fewer than two are judged. */
    public static Idea[] pickPair(List<Idea> ideas, RandomGenerator rng) {
        List<Idea> top = new ArrayList<>(ideas.stream().filter(i -> Score.fitness(i) != null)
                .sorted(Comparator.comparing((Idea i) -> Score.fitness(i)).reversed().thenComparing(Elo::rating, Comparator.reverseOrder()))
                .limit(TOP).toList());
        if (top.size() < 2) return null;
        java.util.Collections.shuffle(top, rng);
        top.sort(Comparator.comparingInt(Idea::duels)); // stable: shuffled order breaks ties
        return new Idea[]{top.get(0), top.get(1)};
    }

    static final JsonNode SCHEMA = Js.parse("""
            {"type":"object","properties":{
              "winner":{"type":"string","description":"\\"A\\", \\"B\\" or \\"tie\\"."},
              "reason":{"type":"string","description":"One sentence: the decisive difference."}},
             "required":["winner","reason"],"additionalProperties":false}""");

    public record Duel(double scoreA, List<String> reasons) {}

    /** Score for x when the judge answered `winner` with x shown in position `xPos` ("A" or "B"). */
    static double scoreFor(String winner, String xPos) {
        String w = Js.trim(winner).toUpperCase(java.util.Locale.ROOT);
        if (w.equals("A") || w.equals("B")) return w.equals(xPos) ? 1 : 0;
        return 0.5;
    }

    public static Llm.Out<Duel> duel(Chat chat, String model, String goal, Idea a, Idea b, String guidance) {
        double cost = 0, score = 0;
        List<String> reasons = new ArrayList<>();
        for (boolean swapped : new boolean[]{false, true}) {
            Idea first = swapped ? b : a, second = swapped ? a : b;
            String user = String.join("\n\n", List.of(
                    Js.trim(goal).isEmpty() ? "" : "The goal: " + Js.trim(goal),
                    Js.trim(guidance == null ? "" : guidance).isEmpty() ? "" : "Jury guidance: " + Js.trim(guidance),
                    "Idea A:\n" + Llm.fmtIdea(first.title(), first.pitch()),
                    "Idea B:\n" + Llm.fmtIdea(second.title(), second.pitch()),
                    "Which idea is better overall, weighing originality, feasibility and impact? Answer \"tie\" only if you truly cannot choose."
            ).stream().filter(s -> !s.isEmpty()).toList());
            ChatResult r = chat.chat(new ChatRequest(model, Llm.JUDGE_SYSTEM, user, "duel", SCHEMA, 0.2, 400, null));
            JsonNode v = Js.absent(r.value()) ? Js.object() : r.value();
            score += scoreFor(Js.str(v.path("winner")), swapped ? "B" : "A");
            String reason = Js.trim(Js.str(v.path("reason")));
            if (!reason.isEmpty()) reasons.add(reason);
            cost += r.costUsd();
        }
        return new Llm.Out<>(new Duel(score / 2, reasons), cost);
    }
}
