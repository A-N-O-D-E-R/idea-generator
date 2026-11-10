package bio.anode.ideagenerator;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One chat interface over many providers (upstream providers.ts). A model is
 * written "provider:model", e.g. "openrouter:anthropic/claude-sonnet-5.5",
 * "anthropic:claude-opus-5-5", "ollama:qwen3:8b". A bare "vendor/model" means OpenRouter.
 *
 * App settings (keys, base URLs, models per role) live in the browser and are
 * pushed to the server, which keeps them in memory only. Environment variables
 * are a fallback for keys.
 */
public final class Providers {
    private Providers() {}

    /** Environment lookup; a seam so tests can fake variables. */
    static Function<String, String> env = System::getenv;

    public enum Provider {
        OPENROUTER("OpenRouter", "openai", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY", true, false, true, null),
        ANTHROPIC("Anthropic", "anthropic", null, "ANTHROPIC_API_KEY", true, false, false, null),
        OPENAI("OpenAI", "openai", "https://api.openai.com/v1", "OPENAI_API_KEY", true, false, false, null),
        AZURE("Azure OpenAI / Microsoft Foundry (Copilot models)", "openai", null, "AZURE_OPENAI_API_KEY", true, false, false,
                "Neither GitHub Copilot nor Microsoft Copilot has a completion API for apps, and GitHub Models was retired on 30 July 2026 (GitHub points to Microsoft Foundry). The models behind both Copilots are reachable here: base URL https://<resource>.openai.azure.com/openai/v1, model = deployment name."),
        OLLAMA("Ollama (local)", "ollama", null, null, false, true, false, null),
        MLX("MLX · mlx_lm.server (local)", "openai", null, null, false, true, false, null),
        LMSTUDIO("LM Studio (local)", "openai", null, null, false, true, false, null),
        CUSTOM("Other OpenAI-compatible", "openai", null, "CUSTOM_API_KEY", false, false, false, null);

        public final String label, type, base, env, note;
        public final boolean needsKey, local;
        /** Can run OpenRouter's web-search plugin (needed for the X scout). */
        public final boolean search;

        Provider(String label, String type, String base, String env, boolean needsKey, boolean local, boolean search, String note) {
            this.label = label;
            this.type = type;
            this.base = base;
            this.env = env;
            this.needsKey = needsKey;
            this.local = local;
            this.search = search;
            this.note = note;
        }

        public String id() {
            return name().toLowerCase();
        }

        public static Provider of(String id) {
            return Arrays.stream(values()).filter(p -> p.id().equals(id)).findFirst().orElse(null);
        }
    }

    public static final List<String> ROLES = List.of("distill", "expand", "combine", "judge", "summary", "research", "x", "brief");

    public record AppSettings(Map<String, String> keys, Map<String, String> baseUrls, Map<String, String> models,
                              String projectsRoot, double maxUsdPerDay,
                              /* Idea workspace (INDEX.md + ideas/<slug>/) that projects are also filed into; "" = off */
                              String workspaceRoot,
                              /* perso/ai-scientist: base URL and personal API key (ais_...); "" = off. Memory only, like the other keys. */
                              String scientistUrl, String scientistKey) {

        static final String SONNET = "openrouter:anthropic/claude-sonnet-5.5";

        public static AppSettings defaults() {
            Map<String, String> baseUrls = new LinkedHashMap<>();
            baseUrls.put("ollama", "http://localhost:11434");
            baseUrls.put("mlx", "http://localhost:8080/v1");
            baseUrls.put("lmstudio", "http://localhost:1234/v1");
            baseUrls.put("azure", "");
            baseUrls.put("custom", "");
            // Sonnet 5.5: calibrated judge, idiomatic Swedish. Grok: the only route that can search X.
            Map<String, String> models = new LinkedHashMap<>();
            for (String r : ROLES) models.put(r, r.equals("x") ? "openrouter:x-ai/grok-4.3" : SONNET);
            return new AppSettings(new LinkedHashMap<>(), baseUrls, models, "~/Projekt/idea-projects", 5, "", "", "");
        }

        public AppSettings withProjectsRoot(String root) {
            return new AppSettings(keys, baseUrls, models, root, maxUsdPerDay, workspaceRoot, scientistUrl, scientistKey);
        }

        public AppSettings withWorkspaceRoot(String root) {
            return new AppSettings(keys, baseUrls, models, projectsRoot, maxUsdPerDay, root, scientistUrl, scientistKey);
        }

        public AppSettings withScientist(String url, String key) {
            return new AppSettings(keys, baseUrls, models, projectsRoot, maxUsdPerDay, workspaceRoot, url, key);
        }
    }

    public record ChatRequest(String model, String system, String user, String schemaName, JsonNode schema,
                              double temperature, Integer maxTokens,
                              /* OpenRouter plugins, e.g. [{id:'web', engine:'native'}] for live search */
                              JsonNode plugins) {
        public ChatRequest(String model, String system, String user, String schemaName, JsonNode schema, double temperature) {
            this(model, system, user, schemaName, schema, temperature, null, null);
        }
    }

    /** citations: URLs the search engine itself returned. costUsd: 0 when the provider does not report it. */
    public record ChatResult(JsonNode value, double costUsd, List<String> citations) {
        public ChatResult(JsonNode value, double costUsd) {
            this(value, costUsd, List.of());
        }
    }

    /** Every LLM call goes through one of these; tests pass a fake. */
    @FunctionalInterface
    public interface Chat {
        ChatResult chat(ChatRequest r);
    }

    public static class ProviderError extends RuntimeException {
        public final String code;

        public ProviderError(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    public record ParsedModel(Provider provider, String model) {}

    public static ParsedModel parseModel(String ref) {
        int i = ref.indexOf(':');
        Provider p = i > 0 ? Provider.of(ref.substring(0, i)) : null;
        return p != null ? new ParsedModel(p, ref.substring(i + 1)) : new ParsedModel(Provider.OPENROUTER, ref);
    }

    /** Merge settings pushed from the browser onto the defaults, keeping only well-typed values. */
    public static AppSettings mergeAppSettings(JsonNode raw) {
        AppSettings d = AppSettings.defaults();
        JsonNode r = raw == null ? Js.object() : raw;
        Map<String, String> keys = new LinkedHashMap<>(d.keys()), baseUrls = new LinkedHashMap<>(d.baseUrls()), models = new LinkedHashMap<>(d.models());
        for (Provider p : Provider.values()) {
            String k = Js.str(r.path("keys").path(p.id()), 500);
            if (!k.isEmpty()) keys.put(p.id(), k);
            JsonNode b = r.path("baseUrls").path(p.id());
            if (b.isString()) baseUrls.put(p.id(), Js.str(b, 500).replaceAll("/+$", ""));
        }
        for (String role : ROLES) {
            String m = Js.str(r.path("models").path(role), 200);
            if (!m.isEmpty()) models.put(role, m);
        }
        String root = Js.str(r.path("projectsRoot"), 500);
        double max = Js.num(r.path("maxUsdPerDay"));
        String workspace = Js.str(r.path("workspaceRoot"), 500);
        return new AppSettings(keys, baseUrls, models,
                root.matches("^(~/|/).*") ? root : d.projectsRoot(),
                Double.isFinite(max) && max >= 0 ? Math.min(1000, max) : d.maxUsdPerDay(),
                workspace.matches("^(~/|/).*") ? workspace : d.workspaceRoot(),
                Js.str(r.path("scientistUrl"), 500).matches("^https?://.*") ? Js.str(r.path("scientistUrl"), 500).replaceAll("/+$", "") : d.scientistUrl(),
                Js.str(r.path("scientistKey"), 500));
    }

    private static String nonEmpty(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Browser-entered key first, then the environment. */
    public static String keyFor(AppSettings s, Provider p) {
        String k = nonEmpty(s.keys().get(p.id()));
        if (k == null && p.env != null) k = nonEmpty(env.apply(p.env));
        return k;
    }

    /** browser | env | null */
    public static String keySource(AppSettings s, Provider p) {
        if (nonEmpty(s.keys().get(p.id())) != null) return "browser";
        if (p.env != null && nonEmpty(env.apply(p.env)) != null) return "env";
        return null;
    }

    private static String baseFor(AppSettings s, Provider p) {
        String b = nonEmpty(s.baseUrls().get(p.id()));
        if (b == null) b = p.base == null ? "" : p.base;
        return b.replaceAll("/+$", "");
    }

    // Anthropic first-party prices (USD per million tokens), for the budget limiter.
    static final Map<String, double[]> ANTHROPIC_PRICES = Map.of(
            "claude-fable-5-1", new double[]{10, 50}, "claude-opus-5-5", new double[]{4, 20}, "claude-opus-5", new double[]{5, 25},
            "claude-sonnet-5-5", new double[]{2, 10}, "claude-sonnet-5", new double[]{2, 10}, "claude-haiku-5-5", new double[]{0.1, 0.5},
            "claude-haiku-4-5", new double[]{1, 5});

    // ---------------------------------------------------------------- JSON from model answers

    private static final Pattern THINK = Pattern.compile("<think>[\\s\\S]*?</think>", Pattern.CASE_INSENSITIVE);
    private static final Pattern FENCE = Pattern.compile("```(?:json)?", Pattern.CASE_INSENSITIVE);

    /** Pull a JSON value out of a model answer: drop think blocks and code fences, take the outermost braces. */
    public static JsonNode extractJson(String text) {
        String t = Js.trim(FENCE.matcher(THINK.matcher(text).replaceAll("")).replaceAll(""));
        try {
            JsonNode n = Js.parse(t);
            if (n != null && !n.isMissingNode()) return n;
        } catch (RuntimeException ignored) {
            // fall through to the braces
        }
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a >= 0 && b > a) return Js.parse(t.substring(a, b + 1));
        throw new RuntimeException("No JSON object in answer: " + Js.cut(t, 160));
    }

    private static String promptJsonRule(JsonNode schema) {
        return "\n\nRespond with ONLY a JSON object that matches this JSON Schema. No prose, no code fences.\n" + Js.stringify(schema);
    }

    // ---------------------------------------------------------------- HTTP

    static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    static HttpResponse<String> send(HttpRequest.Builder req, Map<String, String> headers) throws IOException {
        headers.forEach(req::header);
        try {
            return HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    static HttpResponse<String> post(String url, Map<String, String> headers, JsonNode body, Duration timeout) throws IOException {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put("Content-Type", "application/json");
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).POST(HttpRequest.BodyPublishers.ofString(Js.stringify(body))), h);
    }

    private static boolean ok(int status) {
        return status >= 200 && status < 300;
    }

    private static boolean has(String text, String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static RuntimeException unchecked(IOException e) {
        return new RuntimeException(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), e);
    }

    /** What Node reports as e.cause.code for a failed connection. */
    private static String causeCode(Exception e) {
        if (e instanceof ConnectException || e.getCause() instanceof ConnectException) return "ECONNREFUSED";
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    // ---------------------------------------------------------------- OpenAI-compatible

    private static ChatResult openaiChat(AppSettings settings, Provider p, String model, ChatRequest r) {
        String base = baseFor(settings, p);
        if (base.isEmpty()) throw new ProviderError("no_base_url", p.label + ": set a base URL in Settings");
        String key = keyFor(settings, p);
        if (p.needsKey && key == null) throw new ProviderError("no_key", p.label + ": no API key (Settings → Providers)");
        if (r.plugins() != null && !p.search) {
            throw new ProviderError("no_search", p.label + " cannot search the web; use an OpenRouter model such as openrouter:x-ai/grok-4.3");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (key != null) {
            if (p == Provider.AZURE) headers.put("api-key", key);
            else headers.put("Authorization", "Bearer " + key);
        }
        if (p == Provider.OPENROUTER) {
            headers.put("HTTP-Referer", "http://localhost:8300");
            headers.put("X-Title", "Idea Generator 2");
        }
        boolean schemaMode = p != Provider.MLX;
        ObjectNode body = Js.object();
        body.put("model", model);
        body.put("temperature", r.temperature());
        body.put("max_tokens", r.maxTokens() != null ? r.maxTokens() : 1200);
        if (p == Provider.OPENROUTER) {
            body.putObject("reasoning").put("enabled", false);
            body.putObject("usage").put("include", true);
            if (r.plugins() != null) body.set("plugins", r.plugins());
        }
        String lastError = "";
        for (int attempt = 0, adjustments = 0; attempt < 3; ) {
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", r.system() + (schemaMode ? "" : promptJsonRule(r.schema())));
            messages.addObject().put("role", "user").put("content", r.user());
            if (schemaMode) {
                ObjectNode js = body.putObject("response_format").put("type", "json_schema").putObject("json_schema");
                js.put("name", r.schemaName()).put("strict", true).set("schema", r.schema());
            } else body.remove("response_format");

            HttpResponse<String> res;
            try {
                res = post(base + "/chat/completions", headers, body, Duration.ofSeconds(r.plugins() != null ? 150 : 120));
            } catch (IOException e) {
                throw unchecked(e);
            }
            String text = res.body();
            int status = res.statusCode();
            // Providers disagree on parameters; adapt and retry without spending an attempt.
            if (status == 400 && adjustments < 4) {
                adjustments++;
                if (body.has("reasoning") && has(text, "reasoning")) {
                    body.remove("reasoning"); // this model insists on reasoning; thinking counts against max_tokens
                    if (body.has("max_tokens")) body.put("max_tokens", Math.max(body.get("max_tokens").asInt() * 4, 4000));
                    continue;
                }
                if (body.has("temperature") && has(text, "temperature")) {
                    body.remove("temperature");
                    continue;
                }
                if (body.has("max_tokens") && has(text, "max_tokens") && has(text, "max_completion_tokens")) {
                    body.set("max_completion_tokens", body.remove("max_tokens"));
                    continue;
                }
                if (schemaMode && has(text, "response_format|json_schema|structured")) {
                    schemaMode = false;
                    continue;
                }
            }
            attempt++;
            if (!ok(status)) {
                lastError = p.label + " " + status + ": " + Js.cut(text, 300);
                if (status == 401 || status == 403) throw new ProviderError("bad_key", lastError);
                if (status == 429 || status >= 500) {
                    sleep(1000L * attempt);
                    continue;
                }
                throw new ProviderError("provider_error", lastError);
            }
            JsonNode data;
            try {
                data = Js.parse(text);
            } catch (RuntimeException e) {
                lastError = p.label + ": not JSON: " + Js.cut(text, 120);
                continue;
            }
            JsonNode message = data.path("choices").path(0).path("message");
            JsonNode content = message.path("content");
            List<String> citations = new ArrayList<>();
            for (JsonNode a : Js.arr(message.path("annotations"))) {
                JsonNode url = a.path("url_citation").path("url");
                if ("url_citation".equals(a.path("type").asString("")) && url.isString() && !url.stringValue().isEmpty()) {
                    citations.add(url.stringValue());
                }
            }
            if (Js.absent(content) || (content.isString() && content.stringValue().isEmpty())) {
                lastError = "Empty answer from " + model;
                continue;
            }
            try {
                if (!content.isString()) throw new RuntimeException("content.replace is not a function");
                double cost = Js.num(data.path("usage").path("cost"));
                return new ChatResult(extractJson(content.stringValue()), Double.isFinite(cost) ? cost : 0, citations);
            } catch (RuntimeException e) {
                lastError = e.getMessage();
                schemaMode = false;
            }
        }
        throw new ProviderError("provider_error", lastError.isEmpty() ? p.label + " failed" : lastError);
    }

    // ---------------------------------------------------------------- Ollama (native /api/chat)

    private static ChatResult ollamaChat(AppSettings settings, String model, ChatRequest r) {
        if (r.plugins() != null) throw new ProviderError("no_search", "Ollama cannot search the web; use an OpenRouter model for the X scout");
        String base = baseFor(settings, Provider.OLLAMA);
        // Native API: think:false stops reasoning models (qwen3) spending every token on thinking,
        // and format takes the JSON Schema directly.
        ObjectNode body = Js.object();
        body.put("model", model).put("stream", false).put("think", false).set("format", r.schema());
        body.putObject("options").put("temperature", r.temperature()).put("num_predict", r.maxTokens() != null ? r.maxTokens() : 1200);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", r.system());
        messages.addObject().put("role", "user").put("content", r.user());
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpResponse<String> res;
            try {
                res = post(base + "/api/chat", Map.of(), body, Duration.ofSeconds(300));
            } catch (IOException | IllegalArgumentException e) {
                throw new ProviderError("unreachable", "Ollama at " + base + " is not reachable (" + causeCode(e) + "). Is it running?");
            }
            String text = res.body();
            if (res.statusCode() == 400 && body.has("think") && has(text, "think")) {
                body.remove("think");
                continue;
            }
            if (!ok(res.statusCode())) throw new ProviderError("provider_error", "Ollama " + res.statusCode() + ": " + Js.cut(text, 300));
            try {
                return new ChatResult(extractJson(Js.str(Js.parse(text).path("message").path("content"))), 0);
            } catch (RuntimeException e) {
                if (attempt == 2) throw new ProviderError("provider_error", "Ollama " + model + ": " + e.getMessage());
            }
        }
        throw new ProviderError("provider_error", "Ollama " + model + " failed");
    }

    // ---------------------------------------------------------------- Anthropic (Messages API)

    // What the upstream SDK (@anthropic-ai/sdk 1.3) sends: API version 2026-10-21, key as a Bearer token.
    static final String ANTHROPIC_VERSION = "2026-10-21";

    private static String anthropicBase() {
        String b = nonEmpty(env.apply("ANTHROPIC_BASE_URL"));
        return (b == null ? "https://api.anthropic.com" : b).replaceAll("/+$", "");
    }

    private static Map<String, String> anthropicHeaders(String key) {
        if (key == null) {
            // The SDK refuses to send a request without credentials.
            throw new RuntimeException("Could not resolve authentication method. Expected either apiKey or authToken to be set.");
        }
        return Map.of("Authorization", "Bearer " + key, "anthropic-version", ANTHROPIC_VERSION, "Accept", "application/json");
    }

    /** The SDK's retry policy: two retries on 408/409/429/5xx and on connection errors. */
    private static HttpResponse<String> anthropicSend(Supplier<HttpRequest.Builder> req, Map<String, String> headers) {
        for (int retry = 0; ; retry++) {
            try {
                HttpResponse<String> res = send(req.get(), headers);
                int s = res.statusCode();
                if (retry < 2 && (s == 408 || s == 409 || s == 429 || s >= 500)) {
                    sleep(500L << retry);
                    continue;
                }
                return res;
            } catch (IOException e) {
                if (retry < 2) {
                    sleep(500L << retry);
                    continue;
                }
                throw new ProviderError("provider_error", "Anthropic undefined: Connection error.");
            }
        }
    }

    private static ChatResult anthropicChat(AppSettings settings, String model, ChatRequest r) {
        if (r.plugins() != null) throw new ProviderError("no_search", "The X scout needs OpenRouter (Grok with X search)");
        Map<String, String> headers = new LinkedHashMap<>(anthropicHeaders(keyFor(settings, Provider.ANTHROPIC)));
        headers.put("Content-Type", "application/json");
        // Structured output via output_config.format. No temperature: current models reject
        // non-default sampling. Low effort: these are short, single-shot answers.
        ObjectNode outputConfig = Js.object();
        outputConfig.putObject("format").put("type", "json_schema").set("schema", r.schema());
        outputConfig.put("effort", "low");
        for (int attempt = 0; attempt < 2; attempt++) {
            ObjectNode body = Js.object();
            body.put("model", model);
            body.put("max_tokens", Math.max(r.maxTokens() != null ? r.maxTokens() : 1200, 4000));
            body.put("system", r.system());
            body.putArray("messages").addObject().put("role", "user").put("content", r.user());
            body.set("output_config", outputConfig);
            String json = Js.stringify(body);
            HttpResponse<String> res = anthropicSend(() -> HttpRequest.newBuilder(URI.create(anthropicBase() + "/v1/messages"))
                    .timeout(Duration.ofMinutes(10)).POST(HttpRequest.BodyPublishers.ofString(json)), headers);
            String message = res.statusCode() + " " + res.body(); // the SDK's APIError message
            if (res.statusCode() == 400 && outputConfig.has("effort") && has(message, "effort")) {
                outputConfig.remove("effort"); // older models (e.g. Haiku 4.5) do not take effort
                continue;
            }
            if (res.statusCode() == 401) throw new ProviderError("bad_key", "Anthropic: " + message);
            if (!ok(res.statusCode())) throw new ProviderError("provider_error", "Anthropic " + res.statusCode() + ": " + message);
            JsonNode msg = Js.parse(res.body());
            if ("refusal".equals(msg.path("stop_reason").asString(""))) throw new ProviderError("refused", model + " declined to answer");
            StringBuilder text = new StringBuilder();
            for (JsonNode b : Js.arr(msg.path("content"))) if ("text".equals(b.path("type").asString(""))) text.append(Js.str(b.path("text")));
            double[] price = ANTHROPIC_PRICES.getOrDefault(model, new double[]{0, 0});
            double cost = (msg.path("usage").path("input_tokens").asDouble(0) * price[0]
                    + msg.path("usage").path("output_tokens").asDouble(0) * price[1]) / 1e6;
            return new ChatResult(extractJson(text.toString()), cost);
        }
        throw new ProviderError("provider_error", "Anthropic " + model + " failed");
    }

    /** A chat function that routes every request by its "provider:model" string. */
    public static Chat makeChat(Supplier<AppSettings> getSettings) {
        return r -> {
            AppSettings settings = getSettings.get();
            ParsedModel pm = parseModel(r.model());
            if (pm.model().isEmpty()) throw new ProviderError("bad_model", "No model given in \"" + r.model() + "\"");
            return switch (pm.provider().type) {
                case "anthropic" -> anthropicChat(settings, pm.model(), r);
                case "ollama" -> ollamaChat(settings, pm.model(), r);
                default -> openaiChat(settings, pm.provider(), pm.model(), r);
            };
        };
    }

    // ---------------------------------------------------------------- model lists

    private static JsonNode getJson(String url, Map<String, String> headers) throws IOException {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.putIfAbsent("Accept", "application/json");
        HttpResponse<String> res = send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET(), h);
        String text = res.body();
        if (!ok(res.statusCode())) {
            throw new ProviderError(res.statusCode() == 401 || res.statusCode() == 403 ? "bad_key" : "provider_error",
                    res.statusCode() + ": " + Js.cut(text, 200));
        }
        try {
            return Js.parse(text);
        } catch (RuntimeException e) {
            throw new ProviderError("provider_error", "Not JSON: " + Js.cut(text, 120));
        }
    }

    /** List a provider's models, also used as a connection test. */
    public static List<String> listModels(AppSettings settings, Provider p) {
        String base = baseFor(settings, p);
        String key = keyFor(settings, p);
        try {
            if (p == Provider.ANTHROPIC) {
                // The SDK's paginated models.list(); any failure there surfaces as "unreachable" upstream.
                List<String> out = new ArrayList<>();
                Map<String, String> headers = anthropicHeaders(key);
                String after = null;
                while (true) {
                    String url = anthropicBase() + "/v1/models?limit=20" + (after != null ? "&after_id=" + after : "");
                    HttpResponse<String> res = anthropicSend(() -> HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET(), headers);
                    if (!ok(res.statusCode())) throw new RuntimeException(res.statusCode() + " " + res.body());
                    JsonNode page = Js.parse(res.body());
                    for (JsonNode m : Js.arr(page.path("data"))) out.add(Js.str(m.path("id")));
                    if (!page.path("has_more").asBoolean(false) || Js.absent(page.path("last_id"))) return out;
                    after = page.path("last_id").asString();
                }
            }
            if (p == Provider.OLLAMA) {
                List<String> out = new ArrayList<>();
                for (JsonNode m : Js.arr(getJson(base + "/api/tags", Map.of()).path("models"))) out.add(Js.str(m.path("name")));
                return out;
            }
            if (base.isEmpty()) throw new ProviderError("no_base_url", "Set a base URL first");
            if (p.needsKey && key == null) throw new ProviderError("no_key", "No API key");
            Map<String, String> headers = key == null ? Map.of() : p == Provider.AZURE ? Map.of("api-key", key) : Map.of("Authorization", "Bearer " + key);
            List<String> out = new ArrayList<>();
            for (JsonNode m : Js.arr(getJson(base + "/models", headers).path("data"))) out.add(Js.str(m.path("id")));
            out.sort(null);
            return out;
        } catch (ProviderError e) {
            if (p != Provider.ANTHROPIC) throw e;
            throw new ProviderError("unreachable", "Anthropic: " + e.getMessage());
        } catch (IOException | RuntimeException e) {
            throw new ProviderError("unreachable", p.label + ": " + causeCode(e));
        }
    }
}
