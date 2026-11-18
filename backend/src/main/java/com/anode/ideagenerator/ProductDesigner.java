package com.anode.ideagenerator;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Hand an idea to productDesigner: {@code POST /api/projets/import} with the idea's {@code idea.json}
 * (digital-factory's idea-brief.schema.json). productDesigner validates it, creates the Projet and seeds its Map
 * (problem → Destination, open questions → Questions, out of scope → Hors périmètre, risks and research → Brouillard).
 * Authenticated by a personal key {@code pd_...}: no session, no CSRF token.
 */
public final class ProductDesigner {
    private ProductDesigner() {}

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /**
     * @param source this idea's URL, the traceability key of the Projet (productDesigner refuses the same source twice);
     *               null: productDesigner takes the first URL of the idea's lineage
     * @param shared a shared server's no-redirect client, which re-checks the address on every request
     * @return productDesigner's answer: {id, nom, questions, brouillard, ...}
     */
    public static JsonNode importIdea(String baseUrl, String key, String ideaJson, String source, boolean shared) {
        String query = source == null || source.isBlank() ? "" : "?source=" + URLEncoder.encode(source, StandardCharsets.UTF_8);
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/projets/import" + query))
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(ideaJson));
        HttpResponse<String> res;
        try {
            res = Providers.send(shared ? Providers.HTTP_NO_REDIRECT : HTTP, req,
                    Map.of("Authorization", "Bearer " + key, "Content-Type", "application/json", "Accept", "application/json"));
        } catch (IOException e) {
            throw new AppError(502, "product_designer_unreachable", "productDesigner is not reachable at " + baseUrl + ": " + e.getMessage());
        } catch (Providers.ProviderError e) {
            throw new AppError(400, e.code, e.getMessage());
        }
        JsonNode v;
        try {
            v = Js.parse(res.body());
        } catch (RuntimeException e) {
            v = Js.object();
        }
        int status = res.statusCode();
        if (status / 100 == 2) return v;
        String why = Js.trim(Js.str(v.path("detail")));
        if (why.isEmpty()) why = Js.trim(Js.str(v.path("title")));
        String suffix = why.isEmpty() ? "" : ": " + why;
        switch (status) {
            case 401, 403 -> throw new AppError(502, "product_designer_auth",
                    "productDesigner refused the key (" + status + "): check the pd_ key in the app settings" + suffix);
            case 409 -> throw new AppError(409, "already_imported", "productDesigner already has this idea" + suffix);
            case 422 -> throw new AppError(502, "product_designer_invalid", "productDesigner rejected the idea.json" + suffix);
            default -> throw new AppError(502, "product_designer_error", "productDesigner answered " + status + suffix);
        }
    }
}
