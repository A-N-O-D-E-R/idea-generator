package bio.anode.ideagenerator;

import bio.anode.ideagenerator.Providers.AppSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.stream.Collectors;

/**
 * Calibrate the judge against known-weak and known-strong ideas with the real
 * model (upstream tools/calibrate-judge.ts). Costs money (about 22 judge calls),
 * so it is not part of the tests. Keys come from the environment.
 *
 *   java -cp target/idea-generator-0.1.0.jar -Dloader.main=bio.anode.ideagenerator.CalibrateJudge \
 *        org.springframework.boot.loader.launch.PropertiesLauncher [--model provider:model]
 *
 * Re-run it whenever the judge's prompt, schema or model changes. The weak set
 * includes bare topics ("fire", "electric cars") that an uncalibrated LLM judge
 * rated 3/3 "gold". This judge must not.
 */
public final class CalibrateJudge {
    private CalibrateJudge() {}

    static final List<String> WEAK = List.of(
            "fire",
            "Electric cars",
            "En app för att hitta nya vänner",
            "Använd AI för att förbättra vården",
            "Make cities greener",
            "Solpaneler på taken");

    static final List<String> STRONG = List.of(
            "Reservdelsbibliotek: Folkbiblioteken 3D-skannar och skriver ut trasiga plastdetaljer till hushållsapparater på beställning, så att en bruten lucka inte gör att en fungerande diskmaskin kasseras.",
            "Elmätaren som anhöriglarm: En billig sensor på elmätaren känner igen apparaters strömsignaturer och skickar sms till anhöriga när en äldre persons morgonrutin med vattenkokare och kaffebryggare uteblir.",
            "Skolbussen som paketbil: Landsbygdens skolbussar tar paket och apotekspåsar i ett låst bagageutrymme mellan morgon- och eftermiddagsturen, så att glesbygden får dagliga leveranser utan nya fordon.",
            "Pothole map from bus suspensions: accelerometers already fitted in city buses flag repeated jolts at the same GPS spot, and the road department gets a ranked repair list every morning.",
            "Receipt-to-swap coach: the grocery chain's loyalty app turns each receipt into three concrete swaps for the next shop that cut saturated fat, each costing the same or less.");

    static final int RUNS = 2;
    static final String[] MEDAL = {"–", "🥉", "🥈", "🥇"};

    record Row(String text, List<Llm.Judgement> runs, List<Integer> sums) {}

    static Llm.Pitch asIdea(String text) {
        int i = text.indexOf(": ");
        return i >= 0 ? new Llm.Pitch(text.substring(0, i), text.substring(i + 2)) : new Llm.Pitch(text, "");
    }

    public static void main(String[] args) {
        int at = List.of(args).indexOf("--model");
        String model = at >= 0 && at + 1 < args.length ? args[at + 1] : AppSettings.defaults().models().get("judge");
        Llm llm = new Llm(Providers.makeChat(AppSettings.defaults()));
        DoubleAdder cost = new DoubleAdder();
        System.out.println("Judge model: " + model + ", " + RUNS + " runs per idea\n");

        List<Row> weak, strong;
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            var w = WEAK.stream().map(t -> CompletableFuture.supplyAsync(() -> judge(llm, model, t, cost), ex)).toList();
            var s = STRONG.stream().map(t -> CompletableFuture.supplyAsync(() -> judge(llm, model, t, cost), ex)).toList();
            weak = w.stream().map(CompletableFuture::join).toList();
            strong = s.stream().map(CompletableFuture::join).toList();
        }
        print("WEAK", weak);
        print("STRONG", strong);

        double weakMean = mean(weak), strongMean = mean(strong), gap = strongMean - weakMean;
        int weakMax = weak.stream().flatMap(r -> r.sums().stream()).mapToInt(Integer::intValue).max().orElse(0);
        long strongGold = strong.stream().flatMap(r -> r.sums().stream()).filter(x -> x >= 13).count();
        int spread = List.of(weak, strong).stream().flatMap(List::stream)
                .mapToInt(r -> r.sums().stream().mapToInt(Integer::intValue).max().orElse(0) - r.sums().stream().mapToInt(Integer::intValue).min().orElse(0))
                .max().orElse(0);
        System.out.printf("%nweak mean %.1f, max %d · strong mean %.1f · gap %.1f%n", weakMean, weakMax, strongMean, gap);
        System.out.printf("strong ideas reaching gold: %d of %d · largest run-to-run spread: %d%n", strongGold, strong.size() * RUNS, spread);
        System.out.printf("cost: $%.4f%n", cost.sum());

        List<String> failures = new ArrayList<>();
        if (weakMax >= 9) failures.add("a weak idea reached a medal (sum " + weakMax + " ≥ 9)");
        if (gap < 3) failures.add(String.format("strong and weak are only %.1f apart (need ≥ 3)", gap));
        if (!failures.isEmpty()) {
            System.out.println("\nFAIL: " + String.join("; ", failures));
            System.exit(1);
        }
        System.out.println("\nPASS: weak ideas earn no medal, and the judge separates weak from strong.");
    }

    static Row judge(Llm llm, String model, String text, DoubleAdder cost) {
        List<Llm.Judgement> runs = new ArrayList<>();
        for (int r = 0; r < RUNS; r++) {
            Llm.Out<Llm.Judgement> out = llm.judge(model, "", asIdea(text), "sv", null, null);
            cost.add(out.costUsd());
            runs.add(out.value());
        }
        return new Row(text, runs, runs.stream().map(j -> j.originality() + j.feasibility() + j.impact()).toList());
    }

    static void print(String label, List<Row> rows) {
        System.out.println(label);
        for (Row r : rows) {
            Llm.Judgement j = r.runs().getFirst();
            System.out.println("  " + r.sums().stream().map(x -> String.format("%2d", x)).collect(Collectors.joining(" ")) + "  "
                    + r.sums().stream().map(x -> MEDAL[Score.medalFromSum(x)]).collect(Collectors.joining()) + "  "
                    + "O" + j.originality() + " F" + j.feasibility() + " I" + j.impact() + "  " + Js.cut(r.text(), 48));
            System.out.println("        ↳ " + j.reason());
        }
    }

    static double mean(List<Row> rows) {
        return rows.stream().flatMap(r -> r.sums().stream()).mapToInt(Integer::intValue).sum() / (double) (rows.size() * RUNS);
    }
}
