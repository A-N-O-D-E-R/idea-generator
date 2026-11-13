package bio.anode.ideagenerator;

import bio.anode.ideagenerator.model.Idea;

import lombok.experimental.UtilityClass;
import tools.jackson.databind.JsonNode;

/**
 * Port of public/js/score.js. The judge scores three criteria 1–5; the medal
 * comes from the sum in code, never from the model: bronze from 9, silver
 * from 11, gold from 13.
 */
@UtilityClass
public class Score {
    public static final int BRONZE = 9, SILVER = 11, GOLD = 13;
    // A hand-set medal counts as a typical sum for that medal when breeding.
    private static final int[] OVERRIDE_FITNESS = {6, 10, 12, 14};

    /** Math.round(Number(n)) clamped to 1–5; anything not a number is 1. */
    public static int clampScore(Object n) {
        double d = switch (n) {
            case null -> 0;
            case JsonNode j -> Js.num(j);
            case Number x -> x.doubleValue();
            case String s -> Js.num(s);
            case Boolean b -> b ? 1 : 0;
            default -> Double.NaN;
        };
        if (!Double.isFinite(d)) return 1;
        return (int) Math.min(5, Math.max(1, Math.round(d)));
    }

    public static boolean isJudged(Idea i) {
        return i != null && i.originality() != null && i.feasibility() != null && i.impact() != null;
    }

    public static Integer scoreSum(Idea i) {
        return isJudged(i) ? i.originality() + i.feasibility() + i.impact() : null;
    }

    public static Integer medalFromSum(Integer sum) {
        if (sum == null) return null;
        if (sum >= GOLD) return 3;
        if (sum >= SILVER) return 2;
        if (sum >= BRONZE) return 1;
        return 0;
    }

    /** Medal shown for an idea: a hand-set medal wins over the judge. null = not judged yet. */
    public static Integer medal(Idea i) {
        if (i.medalOverride() != null) return i.medalOverride();
        return medalFromSum(scoreSum(i));
    }

    /** What evolution selects on. null = not part of the population yet. */
    public static Integer fitness(Idea i) {
        if (i.medalOverride() != null) return OVERRIDE_FITNESS[i.medalOverride()];
        return scoreSum(i);
    }
}
