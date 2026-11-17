package com.anode.ideagenerator;

import com.anode.ideagenerator.model.Idea;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Hand an idea to perso/ai-scientist as an ideation job ({@code POST /v1/ideations}):
 * it turns the topic into research ideas with Semantic Scholar novelty checks,
 * from which experiment runs can be started there. The topic markdown is built
 * from the idea itself, no LLM call here. Spend happens on the ai-scientist
 * account's own key, capped by {@link #BUDGET_USD}.
 */
public final class Scientist {
    private Scientist() {}

    /** Kept small: ideation only; the expensive experiment runs are started in ai-scientist by hand. */
    public static final double BUDGET_USD = 2;
    public static final int MAX_GENERATIONS = 3;

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public record Job(String id, String url, String status) {}

    /** The workshop description ai-scientist expects: Title, Keywords, TL;DR, Abstract. */
    public static String topic(Idea i, String goal) {
        String pitch = Js.trim(i.pitch() == null ? "" : i.pitch());
        String tldr = pitch.isEmpty() ? i.title() : pitch.split("(?<=[.!?])\\s+", 2)[0];
        List<String> abs = new ArrayList<>();
        if (!pitch.isEmpty()) abs.add(pitch);
        if (!Js.trim(goal).isEmpty()) abs.add("Context: " + Js.trim(goal));
        if (i.reason() != null && !i.reason().isEmpty()) abs.add("Assessment so far: " + i.reason());
        return String.join("\n", "# Title: " + i.title(), "",
                "## Keywords", String.join(", ", i.keywords().isEmpty() ? List.of(i.title()) : i.keywords()), "",
                "## TL;DR", tldr, "",
                "## Abstract", String.join("\n\n", abs.isEmpty() ? List.of(i.title()) : abs), "");
    }

    public static Job submit(String baseUrl, String key, Idea idea, String goal) {
        return submit(baseUrl, key, idea, goal, false);
    }

    /** shared: a shared server's no-redirect client, which re-checks the address on every request. */
    public static Job submit(String baseUrl, String key, Idea idea, String goal, boolean shared) {
        String body = Js.stringify(Js.obj("topic", topic(idea, goal), "maxGenerations", MAX_GENERATIONS, "budgetUsd", BUDGET_USD));
        JsonNode v = send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/ideations")).timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body)), baseUrl, key, shared);
        String id = Js.str(v.path("id"));
        return new Job(id, baseUrl + "/v1/jobs/" + id, Js.str(v.path("status")));
    }

    /** A job as {@code GET /v1/jobs/{id}} answers: QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELLED or REJECTED; {@code result} when DONE. */
    public record State(String status, JsonNode result, String error) {
        public boolean ended() {
            return Set.of("DONE", "FAILED", "CANCELLED", "REJECTED").contains(status);
        }
    }

    public static State fetch(String baseUrl, String key, String jobId, boolean shared) {
        JsonNode v = send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/jobs/" + jobId)).timeout(Duration.ofSeconds(30)).GET(), baseUrl, key, shared);
        String error = Js.trim(Js.str(v.path("error")));
        if (error.isEmpty() && !v.path("rejection").isMissingNode() && !v.path("rejection").isNull()) error = "rejected: " + Js.cut(v.path("rejection").toString(), 300);
        return new State(Js.str(v.path("status")), v.path("result"), error);
    }

    /** What an ideation job found: its ideas' short hypotheses, related work and risk factors (ai-scientist's idea JSON field names). */
    public record Findings(List<String> hypotheses, String relatedWork, List<String> riskFactors) {}

    public static Findings findings(JsonNode result) {
        List<String> hypotheses = new ArrayList<>(), risks = new ArrayList<>(), related = new ArrayList<>();
        for (JsonNode idea : Js.arr(result)) {
            String h = Js.trim(Js.str(idea.path("Short Hypothesis")));
            if (!h.isEmpty()) hypotheses.add(Js.cut(h, 600));
            String w = Js.trim(Js.str(idea.path("Related Work")));
            if (!w.isEmpty()) related.add(Js.cut(Js.trim(Js.str(idea.path("Title"))) + ": " + w, 1500));
            String r = Js.trim(Js.str(idea.path("Risk Factors and Limitations")));
            if (!r.isEmpty()) risks.add(Js.cut(r, 600));
        }
        return new Findings(hypotheses, Js.cut(String.join("\n\n", related), 4000), risks);
    }

    /** The judge's originality (1-5) once it has read the related work, as the idea-brief contract's verdict. */
    public static String verdict(Integer originality) {
        if (originality == null) return "unclear";
        return originality >= 4 ? "novel" : originality == 3 ? "incremental" : "known";
    }

    private static JsonNode send(HttpRequest.Builder req, String baseUrl, String key, boolean shared) {
        HttpResponse<String> res;
        try {
            res = Providers.send(shared ? Providers.HTTP_NO_REDIRECT : HTTP, req,
                    Map.of("Authorization", "Bearer " + key, "Content-Type", "application/json", "Accept", "application/json"));
        } catch (IOException e) {
            throw new AppError(502, "scientist_unreachable", "AI Scientist is not reachable at " + baseUrl + ": " + e.getMessage());
        } catch (Providers.ProviderError e) {
            throw new AppError(400, e.code, e.getMessage());
        }
        JsonNode v;
        try {
            v = Js.parse(res.body());
        } catch (RuntimeException e) {
            v = Js.object();
        }
        if (res.statusCode() / 100 != 2) {
            // RFC 9457 problem details: detail, else title
            String why = Js.trim(Js.str(v.path("detail")));
            if (why.isEmpty()) why = Js.trim(Js.str(v.path("title")));
            throw new AppError(502, res.statusCode() == 401 ? "scientist_auth" : "scientist_error",
                    "AI Scientist answered " + res.statusCode() + (why.isEmpty() ? "" : ": " + why));
        }
        return v;
    }
}
