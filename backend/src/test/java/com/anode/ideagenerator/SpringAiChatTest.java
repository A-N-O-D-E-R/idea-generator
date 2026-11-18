package com.anode.ideagenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anode.ideagenerator.Providers.AppSettings;
import com.anode.ideagenerator.Providers.ChatRequest;
import com.anode.ideagenerator.Providers.ChatResult;
import com.anode.ideagenerator.Providers.ProviderError;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The Spring AI chat models beyond ProvidersTest: OpenRouter's search plugin, citations and cost, retries, per-tenant keys, the shared-server transport. */
class SpringAiChatTest {
    record Seen(String path, Headers headers, JsonNode body) {}

    record Answer(int status, Object body, Map<String, String> headers) {
        Answer(int status, Object body) {
            this(status, body, Map.of());
        }
    }

    static final List<Seen> seen = new CopyOnWriteArrayList<>();
    static volatile Function<Seen, Answer> script;
    static HttpServer server;
    static String base;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Seen s = new Seen(ex.getRequestURI().toString(), ex.getRequestHeaders(), raw.isEmpty() ? null : Js.parse(raw));
            seen.add(s);
            Answer a = script.apply(s);
            byte[] out = (a.body() instanceof String str ? str : Js.stringify(a.body())).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            a.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            ex.sendResponseHeaders(a.status(), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @BeforeEach
    void reset() {
        seen.clear();
        ChatModels.clear();
        script = s -> new Answer(404, Map.of());
    }

    @AfterEach
    void restore() {
        ChatModels.publicCheck = Providers::requirePublic;
    }

    static final JsonNode SCHEMA = Js.parse("{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"}},\"required\":[\"title\"],\"additionalProperties\":false}");

    static ChatRequest req(String model) {
        return new ChatRequest(model, "sys", "usr", "idea", SCHEMA, 0.9);
    }

    static AppSettings settings(Map<String, String> baseUrls, Map<String, String> keys, boolean shared) {
        AppSettings d = AppSettings.defaults();
        Map<String, String> b = new LinkedHashMap<>(d.baseUrls());
        b.putAll(baseUrls);
        return new AppSettings(new LinkedHashMap<>(keys), b, d.models(), d.projectsRoot(), d.maxUsdPerDay(), d.workspaceRoot(), d.scientistUrl(), d.scientistKey(),
                d.productDesignerUrl(), d.productDesignerKey(), d.reposRoots(), shared);
    }

    static Map<String, Object> completion(String content, Object extraMessage) {
        Map<String, Object> message = new LinkedHashMap<>(Map.of("role", "assistant", "content", content));
        if (extraMessage != null) message.put("annotations", extraMessage);
        return Map.of("id", "c1", "object", "chat.completion", "created", 1, "model", "m",
                "choices", List.of(Map.of("index", 0, "finish_reason", "stop", "message", message)),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15, "cost", 0.0123));
    }

    @Test
    void openRouterSearchPluginCitationsCostAndItsHeaders() {
        script = s -> new Answer(200, completion("{\"title\":\"Trend\"}", List.of(
                Map.of("type", "url_citation", "url_citation", Map.of("url", "https://x.com/a/status/1", "title", "t", "start_index", 0, "end_index", 1)),
                Map.of("type", "url_citation", "url_citation", Map.of("url", "https://x.com/b/status/2", "title", "u", "start_index", 0, "end_index", 1)))));
        // OpenRouter's base URL is fixed (the provider's own); the key is the tenant's
        ChatRequest withSearch = new ChatRequest("openrouter:x-ai/grok-4.3", "sys", "usr", "x", SCHEMA, 0.9, null, Js.parse("[{\"id\":\"web\",\"engine\":\"native\"}]"));
        ChatResult out = Providers.makeChat(settings(Map.of("openrouter", base), Map.of("openrouter", "sk-or-1"), false)).chat(withSearch);

        assertEquals(Js.parse("{\"title\":\"Trend\"}"), out.value());
        assertEquals(0.0123, out.costUsd(), 1e-12);
        assertEquals(List.of("https://x.com/a/status/1", "https://x.com/b/status/2"), out.citations());
        Seen s = seen.getLast();
        assertEquals("Bearer sk-or-1", s.headers().getFirst("authorization"));
        assertEquals("Idea Generator 2", s.headers().getFirst("x-title"));
        assertEquals(Js.parse("[{\"id\":\"web\",\"engine\":\"native\"}]"), s.body().path("plugins"));
        assertFalse(s.body().path("reasoning").path("enabled").asBoolean(true), "reasoning is switched off");
        assertTrue(s.body().path("usage").path("include").asBoolean(), "cost is asked for");
    }

    @Test
    void aModelThatInsistsOnReasoningGetsItBackWithMoreTokens() {
        script = s -> s.body().has("reasoning") ? new Answer(400, Map.of("error", "This model requires reasoning to be enabled"))
                : new Answer(200, completion("{\"title\":\"Tänkte\"}", null));
        ChatResult out = Providers.makeChat(settings(Map.of("openrouter", base), Map.of("openrouter", "k"), false)).chat(req("openrouter:vendor/thinker"));
        assertEquals(Js.parse("{\"title\":\"Tänkte\"}"), out.value());
        assertEquals(2, seen.size());
        assertEquals(1200 * 4, seen.getLast().body().path("max_tokens").asInt(), "thinking counts against max_tokens");
    }

    @Test
    void aModelThatWantsMaxCompletionTokensIsAskedThatWay() {
        script = s -> s.body().has("max_tokens") ? new Answer(400, Map.of("error", "Unsupported parameter: 'max_tokens' is not supported, use 'max_completion_tokens'"))
                : new Answer(200, completion("{\"title\":\"Nytt\"}", null));
        ChatResult out = Providers.makeChat(settings(Map.of("custom", base), Map.of(), false)).chat(req("custom:new-model"));
        assertEquals(Js.parse("{\"title\":\"Nytt\"}"), out.value());
        assertEquals(1200, seen.getLast().body().path("max_completion_tokens").asInt());
        assertFalse(seen.getLast().body().has("max_tokens"));
    }

    @Test
    void rateLimitsAreRetriedAndAPersistentOneIsReported() {
        AtomicInteger calls = new AtomicInteger();
        script = s -> calls.incrementAndGet() < 2 ? new Answer(429, Map.of("error", "slow down")) : new Answer(200, completion("{\"title\":\"Sedan\"}", null));
        assertEquals(Js.parse("{\"title\":\"Sedan\"}"), Providers.makeChat(settings(Map.of("custom", base), Map.of(), false)).chat(req("custom:m")).value());
        assertEquals(2, seen.size());

        seen.clear();
        script = s -> new Answer(500, Map.of("error", "boom"));
        ProviderError e = assertThrows(ProviderError.class, () -> Providers.makeChat(settings(Map.of("custom", base), Map.of(), false)).chat(req("custom:m")));
        assertEquals("provider_error", e.code);
        assertEquals(3, seen.size(), "three attempts");
    }

    @Test
    void eachTenantsKeyGoesWithItsOwnRequests() {
        script = s -> new Answer(200, completion("{\"title\":\"x\"}", null));
        var alice = Providers.makeChat(settings(Map.of("custom", base), Map.of("custom", "key-alice"), false));
        var bob = Providers.makeChat(settings(Map.of("custom", base), Map.of("custom", "key-bob"), false));
        alice.chat(req("custom:m"));
        bob.chat(req("custom:m"));
        alice.chat(req("custom:m"));
        assertEquals(List.of("Bearer key-alice", "Bearer key-bob", "Bearer key-alice"), seen.stream().map(s -> s.headers().getFirst("authorization")).toList());
    }

    @Test
    void aSharedServerNeverFollowsARedirectNorAcceptsAnOversizedAnswer() throws Exception {
        // the loopback fake server stands for a public host: only the address check is bypassed, the transport is the shared-server one
        ChatModels.publicCheck = url -> { };
        HttpServer inside = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger reached = new AtomicInteger();
        inside.createContext("/", ex -> {
            reached.incrementAndGet();
            byte[] out = Js.stringify(completion("{\"title\":\"INSIDE\"}", null)).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        inside.start();
        try {
            script = s -> new Answer(307, Map.of(), Map.of("Location", "http://127.0.0.1:" + inside.getAddress().getPort() + "/v1/chat/completions"));
            ProviderError e = assertThrows(ProviderError.class,
                    () -> Providers.makeChat(settings(Map.of("custom", base), Map.of(), true)).chat(req("custom:m")));
            assertEquals("provider_error", e.code);
            assertTrue(e.getMessage().contains("307"), e.getMessage());
            assertEquals(0, reached.get(), "the redirect was not followed");

            // the same redirect on a single-user server is followed, as a browser would
            ChatModels.clear();
            assertEquals(Js.parse("{\"title\":\"INSIDE\"}"), Providers.makeChat(settings(Map.of("custom", base), Map.of(), false)).chat(req("custom:m")).value());
            assertEquals(1, reached.get());

            // a hostile answer larger than the cap is refused instead of buffered
            ChatModels.clear();
            String huge = "x".repeat(Providers.MAX_BODY + 1024);
            script = s -> new Answer(200, "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"" + huge + "\"}}]}");
            RuntimeException big = assertThrows(RuntimeException.class,
                    () -> Providers.makeChat(settings(Map.of("custom", base), Map.of(), true)).chat(req("custom:m")));
            assertTrue(big.getMessage().contains("larger than"), big.getMessage());
        } finally {
            inside.stop(0);
        }
    }

    @Test
    void aMissingAnthropicKeyIsExplainedAndARefusalIsReported() {
        // no key: nothing is sent
        var noKey = Providers.makeChat(settings(Map.of(), Map.of(), false));
        Providers.env = k -> null;
        try {
            RuntimeException e = assertThrows(RuntimeException.class, () -> noKey.chat(req("anthropic:claude-sonnet-5-5")));
            assertTrue(e.getMessage().contains("authentication"), e.getMessage());
            assertEquals(0, seen.size());
        } finally {
            Providers.env = System::getenv;
        }
    }
}
