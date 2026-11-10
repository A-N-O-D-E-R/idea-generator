package bio.anode.ideagenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bio.anode.ideagenerator.Providers.AppSettings;
import bio.anode.ideagenerator.Providers.ChatRequest;
import bio.anode.ideagenerator.Providers.ChatResult;
import bio.anode.ideagenerator.Providers.Provider;
import bio.anode.ideagenerator.Providers.ProviderError;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Provider adapters against a local fake server: the request shapes we send and the fallbacks we take. */
class ProvidersTest {

    record Seen(String path, Headers headers, JsonNode body) {}

    record Answer(int status, Object body) {}

    static final List<Seen> seen = new CopyOnWriteArrayList<>();
    static volatile Function<Seen, Answer> script;
    static HttpServer server;
    static String base;
    static final Map<String, String> env = new HashMap<>();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Seen s = new Seen(ex.getRequestURI().toString(), ex.getRequestHeaders(), raw.isEmpty() ? null : Js.parse(raw));
            seen.add(s);
            Answer a = script != null ? script.apply(s) : new Answer(404, Map.of("error", "no script"));
            byte[] out = Js.stringify(a.body()).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(a.status(), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        // The Anthropic adapter reads its base URL from the environment, as the SDK does.
        env.put("ANTHROPIC_BASE_URL", base);
        Providers.env = env::get;
    }

    @AfterAll
    static void stop() {
        server.stop(0);
        Providers.env = System::getenv;
    }

    @AfterEach
    void reset() {
        env.remove("OPENAI_API_KEY");
    }

    static final JsonNode SCHEMA = Js.parse("""
            {"type":"object","properties":{"title":{"type":"string"}},"required":["title"],"additionalProperties":false}""");

    static ChatRequest req(String model) {
        return new ChatRequest(model, "sys", "usr", "idea", SCHEMA, 0.9);
    }

    static AppSettings settingsWith(Map<String, String> baseUrls, Map<String, String> keys) {
        AppSettings d = AppSettings.defaults();
        Map<String, String> b = new LinkedHashMap<>(d.baseUrls());
        b.putAll(baseUrls);
        return new AppSettings(new LinkedHashMap<>(keys), b, d.models(), d.projectsRoot(), d.maxUsdPerDay());
    }

    static Map<String, Object> oaiAnswer(String content) {
        return Map.of("choices", List.of(Map.of("message", Map.of("content", content))), "usage", Map.of("cost", 0.002));
    }

    @Test
    void modelReferences() {
        assertEquals(new Providers.ParsedModel(Provider.OPENROUTER, "anthropic/claude-sonnet-5.5"), Providers.parseModel("openrouter:anthropic/claude-sonnet-5.5"));
        assertEquals(new Providers.ParsedModel(Provider.OLLAMA, "qwen3:8b"), Providers.parseModel("ollama:qwen3:8b"));
        assertEquals(new Providers.ParsedModel(Provider.OPENROUTER, "anthropic/claude-sonnet-5.5"), Providers.parseModel("anthropic/claude-sonnet-5.5"));
        assertEquals(new Providers.ParsedModel(Provider.ANTHROPIC, "claude-opus-5-5"), Providers.parseModel("anthropic:claude-opus-5-5"));
    }

    @Test
    void settingsFromTheBrowserAreMergedJunkDropped() {
        AppSettings s = Providers.mergeAppSettings(Js.parse("""
                {"keys":{"openrouter":" sk-1 ","nope":"x"},"baseUrls":{"ollama":"http://box:11434///"},
                 "models":{"judge":"ollama:qwen3:8b","bogus":"x"},"projectsRoot":"relative/path","maxUsdPerDay":-1}"""));
        AppSettings d = AppSettings.defaults();
        assertEquals("sk-1", s.keys().get("openrouter"));
        assertNull(s.keys().get("nope"));
        assertEquals("http://box:11434", s.baseUrls().get("ollama"));
        assertEquals("ollama:qwen3:8b", s.models().get("judge"));
        assertEquals(d.models().get("expand"), s.models().get("expand"));
        assertEquals(d.projectsRoot(), s.projectsRoot(), "a relative path is refused");
        assertEquals(d.maxUsdPerDay(), s.maxUsdPerDay());
    }

    @Test
    void aKeyTypedInTheBrowserWinsOverTheEnvironment() {
        env.put("OPENAI_API_KEY", "from-env");
        assertEquals("from-env", Providers.keyFor(settingsWith(Map.of(), Map.of()), Provider.OPENAI));
        assertEquals("from-browser", Providers.keyFor(settingsWith(Map.of(), Map.of("openai", "from-browser")), Provider.OPENAI));
    }

    @Test
    void jsonIsDugOutOfThinkingTagsFencesAndProse() {
        assertEquals(Js.parse("{\"a\":1}"), Providers.extractJson("<think>hmm {no}</think>\n```json\n{\"a\":1}\n```"));
        assertEquals(Js.parse("{\"a\":{\"b\":2}}"), Providers.extractJson("Here you go: {\"a\":{\"b\":2}} hope that helps"));
        assertThrows(RuntimeException.class, () -> Providers.extractJson("no json here"));
    }

    @Test
    void openAiCompatibleStrictSchemaBearerKeyCost() {
        script = s -> new Answer(200, oaiAnswer("{\"title\":\"Ok\"}"));
        ChatResult out = Providers.makeChat(() -> settingsWith(Map.of("custom", base), Map.of("custom", "k1"))).chat(req("custom:my-model"));
        assertEquals(Js.parse("{\"title\":\"Ok\"}"), out.value());
        assertEquals(0.002, out.costUsd());
        Seen s = seen.getLast();
        assertEquals("/chat/completions", s.path());
        assertEquals("Bearer k1", s.headers().getFirst("authorization"));
        assertEquals("my-model", s.body().path("model").asString());
        assertEquals("json_schema", s.body().path("response_format").path("type").asString());
        assertTrue(s.body().path("response_format").path("json_schema").path("strict").asBoolean());
    }

    @Test
    void openAiCompatibleFallsBackToPromptJsonAndDropsTemperature() {
        seen.clear();
        script = s -> {
            if (s.body().has("response_format")) return new Answer(400, Map.of("error", "response_format json_schema is not supported"));
            if (s.body().has("temperature")) return new Answer(400, Map.of("error", "Unsupported value: temperature"));
            return new Answer(200, oaiAnswer("Sure! {\"title\":\"Fallback\"}"));
        };
        ChatResult out = Providers.makeChat(() -> settingsWith(Map.of("lmstudio", base), Map.of())).chat(req("lmstudio:local-model"));
        assertEquals(Js.parse("{\"title\":\"Fallback\"}"), out.value());
        assertEquals(3, seen.size());
        assertTrue(seen.get(2).body().path("messages").path(0).path("content").asString().contains("JSON Schema"), "the schema moved into the prompt");
        assertNull(seen.get(2).headers().getFirst("authorization"), "local providers need no key");
    }

    @Test
    void azureUsesApiKeyHeaderAnd401IsABadKey() {
        seen.clear();
        script = s -> new Answer(401, Map.of("error", "bad key"));
        ProviderError e = assertThrows(ProviderError.class,
                () -> Providers.makeChat(() -> settingsWith(Map.of("azure", base), Map.of("azure", "az"))).chat(req("azure:my-deployment")));
        assertEquals("bad_key", e.code);
        assertEquals(1, seen.size());
        assertEquals("az", seen.getFirst().headers().getFirst("api-key"));
        assertNull(seen.getFirst().headers().getFirst("authorization"));
    }

    @Test
    void missingKeyAndMissingSearchAreExplainedNotSent() {
        var chat = Providers.makeChat(() -> settingsWith(Map.of(), Map.of()));
        assertEquals("no_key", assertThrows(ProviderError.class, () -> chat.chat(req("openai:gpt-5.4-mini"))).code);
        ChatRequest r = req("ollama:qwen3:8b");
        ChatRequest withSearch = new ChatRequest(r.model(), r.system(), r.user(), r.schemaName(), r.schema(), r.temperature(), null,
                Js.parse("[{\"id\":\"web\"}]"));
        assertEquals("no_search", assertThrows(ProviderError.class, () -> chat.chat(withSearch)).code);
    }

    @Test
    void ollamaNativeApiWithThinkFalseAndSchemaAsFormat() {
        seen.clear();
        script = s -> s.body().has("think") && s.body().path("model").asString().equals("no-think")
                ? new Answer(400, Map.of("error", "\"no-think\" does not support thinking"))
                : new Answer(200, Map.of("message", Map.of("content", "{\"title\":\"Lokal\"}")));
        var chat = Providers.makeChat(() -> settingsWith(Map.of("ollama", base), Map.of()));
        ChatResult out = chat.chat(req("ollama:qwen3:8b"));
        assertEquals(Js.parse("{\"title\":\"Lokal\"}"), out.value());
        assertEquals(0, out.costUsd());
        Seen first = seen.getFirst();
        assertEquals("/api/chat", first.path());
        assertFalse(first.body().path("think").asBoolean(true));
        assertEquals(SCHEMA, first.body().path("format"));
        assertFalse(first.body().path("stream").asBoolean(true));
        chat.chat(req("ollama:no-think"));
        assertFalse(seen.getLast().body().has("think"), "retried without think");
    }

    @Test
    void anthropicOutputConfigLowEffortNoTemperatureCostFromUsage() {
        seen.clear();
        script = s -> new Answer(200, Js.parse("""
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5","stop_reason":"end_turn","stop_sequence":null,
                 "content":[{"type":"text","text":"{\\"title\\":\\"Från Claude\\"}"}],"usage":{"input_tokens":1000,"output_tokens":100}}"""));
        ChatResult out = Providers.makeChat(() -> settingsWith(Map.of(), Map.of("anthropic", "sk-ant-test"))).chat(req("anthropic:claude-sonnet-5-5"));
        assertEquals(Js.parse("{\"title\":\"Från Claude\"}"), out.value());
        assertEquals((1000 * 2 + 100 * 10) / 1e6, out.costUsd(), 1e-12);
        Seen s = seen.getLast();
        assertEquals("/v1/messages", s.path());
        assertTrue("Bearer sk-ant-test".equals(s.headers().getFirst("authorization")) || "sk-ant-test".equals(s.headers().getFirst("x-api-key")));
        assertEquals(Js.parse("{\"format\":{\"type\":\"json_schema\",\"schema\":" + Js.stringify(SCHEMA) + "},\"effort\":\"low\"}"),
                s.body().path("output_config"));
        assertFalse(s.body().has("temperature"));
        assertEquals("sys", s.body().path("system").asString());
    }

    @Test
    void modelListsDoubleAsAConnectionTest() {
        script = s -> s.path().equals("/api/tags")
                ? new Answer(200, Map.of("models", List.of(Map.of("name", "qwen3:8b"), Map.of("name", "gemma3:4b"))))
                : new Answer(200, Map.of("data", List.of(Map.of("id", "b"), Map.of("id", "a"))));
        assertEquals(List.of("qwen3:8b", "gemma3:4b"), Providers.listModels(settingsWith(Map.of("ollama", base), Map.of()), Provider.OLLAMA));
        assertEquals(List.of("a", "b"), Providers.listModels(settingsWith(Map.of("mlx", base), Map.of()), Provider.MLX));
        ProviderError e = assertThrows(ProviderError.class,
                () -> Providers.listModels(settingsWith(Map.of("mlx", "http://127.0.0.1:1"), Map.of()), Provider.MLX));
        assertEquals("unreachable", e.code);
    }
}
