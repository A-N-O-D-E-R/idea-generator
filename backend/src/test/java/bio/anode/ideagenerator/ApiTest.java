package bio.anode.ideagenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bio.anode.ideagenerator.Providers.AppSettings;
import bio.anode.ideagenerator.Providers.ChatRequest;
import bio.anode.ideagenerator.Providers.ChatResult;
import bio.anode.ideagenerator.Providers.ProviderError;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The whole HTTP API against a temporary database and a fake LLM (upstream test/api.test.ts). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "idea.autopilot=false")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiTest {

    static final List<ChatRequest> calls = new CopyOnWriteArrayList<>();
    static volatile int[] judgeScores = {3, 3, 3};
    static volatile List<String> judgeKeywords = List.of("Public Space", "sound");
    static final AtomicReference<RuntimeException> failNext = new AtomicReference<>();
    static volatile List<String> xCitations = List.of();
    static final List<String> revealed = new CopyOnWriteArrayList<>();
    static final Path projectsRoot;
    static final Path dbFile;

    static {
        try {
            projectsRoot = Files.createTempDirectory("ideagen-projects-");
            dbFile = Files.createTempDirectory("ideagen-db-").resolve("test.db");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbFile);
    }

    /** Fake chat: answers by schema name, records every request. */
    static ChatResult fakeChat(ChatRequest r) {
        calls.add(r);
        RuntimeException e = failNext.getAndSet(null);
        if (e != null) throw e;
        int n = calls.size();
        String value = switch (r.schemaName()) {
            case "expand" -> "{\"ideas\":[" + IntStream.rangeClosed(1, 3)
                    .mapToObj(k -> "{\"title\":\"\\\"Barn " + n + "." + k + ".\\\"\",\"pitch\":\"Pitch " + k + "\"}")
                    .collect(Collectors.joining(",")) + "]}";
            case "judgement" -> "{\"reason\":\"Stark mekanism, oklar kund.\",\"originality\":" + judgeScores[0] + ",\"feasibility\":"
                    + judgeScores[1] + ",\"impact\":" + judgeScores[2] + ",\"keywords\":" + Js.stringify(judgeKeywords) + "}";
            case "summary" -> """
                    {"headline":"Tavlan rör sig mot ljud.","themes":[{"name":"Ljud","insight":"Allt låter.","ideas":["Lånecyklar"]}],
                     "strongest":[{"title":"Korsning","why":"Konkret."}],"gaps":["Ingen tänker på natten."],"nextSteps":["Korsa A och B."]}""";
            case "synthesis" -> """
                    {"headline":"Bänkarna dominerar.","insights":["i1","i2","i3"],"standouts":[{"title":"Sjungande parkbänk","board":"X","why":"w"}],
                     "opportunities":["o1","o2"],"nextSteps":["n1","n2"]}""";
            case "brief" -> """
                    {"name":"Sjungande bänk","slug":"Sjungande Bänk!","oneLiner":"En bänk som sjunger.","problem":"P","audience":"A",
                     "mvp":["m1","m2"],"outOfScope":["o"],"stack":"s","firstSteps":["f1","f2"],"openQuestions":["q1"],"risks":["r1"]}""";
            case "leads" -> """
                    {"leads":[{"title":"Trend A","summary":"S","hook":"H","url":"https://x.com/a/status/1"},
                     {"title":"Trend B","summary":"S","hook":"H","url":"https://twitter.com/b/status/2?s=20"},
                     {"title":"Made up","summary":"S","hook":"H","url":"https://x.com/fake/status/3"}]}""";
            case "research" -> """
                    {"leads":[{"id":"2610.1","title":"Rumsljud","summary":"S","hook":"H"},{"id":"9999.9","title":"Påhittad","summary":"S","hook":"H"}]}""";
            default -> "{\"title\":\"" + (r.schemaName().equals("seed") ? "Destillerat frö" : "Korsning") + "\",\"pitch\":\"En kombinerad pitch.\"}";
        };
        return new ChatResult(Js.parse(value), 0.001, r.schemaName().equals("leads") ? xCitations : List.of());
    }

    static final Map<String, Extract.Page> pages = Map.of(
            "https://example.com/artikel", new Extract.Page("Artikel", "Lång artikeltext om lånecyklar. ".repeat(10)),
            "https://example.com/tom", new Extract.Page("", "kort"));

    @TestConfiguration
    static class Fakes {
        @Bean
        Engine.Io io() {
            return new Engine.Io(ApiTest::fakeChat,
                    url -> {
                        Extract.Page p = pages.get(url);
                        if (p == null) throw new RuntimeException("HTTP 404");
                        return p;
                    },
                    goal -> List.of(new Leads.Paper("2610.1", "Spatial audio", "sum", "2026-10-30", 9),
                            new Leads.Paper("2610.2", "Other", "sum", "2026-10-29", 1)),
                    revealed::add, false);
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    Repo repo;
    @Autowired
    Engine engine;
    @Autowired
    RuntimeSettings runtime;

    static AppSettings testSettings() {
        return AppSettings.defaults().withProjectsRoot(projectsRoot.toString());
    }

    @BeforeEach
    void settings() {
        if (!runtime.get().projectsRoot().equals(projectsRoot.toString())) runtime.set(testSettings());
    }

    // ---------------------------------------------------------------- helpers

    record Res(int status, String type, String text) {
        JsonNode data() {
            return Js.parse(text);
        }
    }

    final HttpClient http = HttpClient.newHttpClient();

    String base() {
        return "http://127.0.0.1:" + port;
    }

    Res api(String method, String path, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path));
            if (body != null) {
                b.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : Js.stringify(body)));
            } else b.method(method, HttpRequest.BodyPublishers.noBody());
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Res(res.statusCode(), res.headers().firstValue("content-type").orElse(""), res.body());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    Res api(String method, String path) {
        return api(method, path, null);
    }

    JsonNode newBoard(String extraJson) {
        ObjectNode body = (ObjectNode) Js.parse(extraJson);
        if (!body.has("lang")) body.put("lang", "sv");
        return api("POST", "/api/boards", body).data().path("board");
    }

    JsonNode newBoard() {
        return newBoard("{}");
    }

    JsonNode seed(long boardId, String text) {
        return api("POST", "/api/boards/" + boardId + "/seed", Map.of("text", text)).data().path("idea");
    }

    static ChatRequest lastCall(String schema) {
        for (int i = calls.size() - 1; i >= 0; i--) if (calls.get(i).schemaName().equals(schema)) return calls.get(i);
        throw new AssertionError("no " + schema + " call");
    }

    static long id(JsonNode n) {
        return n.path("id").asLong();
    }

    static List<String> texts(JsonNode arr, String field) {
        return Js.arr(arr).stream().map(x -> x.path(field).asString()).toList();
    }

    static boolean has(String text, String regex) {
        return Pattern.compile(regex, Pattern.MULTILINE).matcher(text).find();
    }

    // ---------------------------------------------------------------- tests

    @Test
    @Order(1)
    void fullLoopSeedJudgeExpandCombineExport() {
        judgeScores = new int[]{3, 3, 3};
        JsonNode board = newBoard("{\"goal\":\"Färre bilresor i staden\"}");
        assertEquals("Ny tavla", board.path("title").asString());
        assertEquals("manual", board.path("config").path("mode").asString());
        assertEquals("Uppfinnaren · Inventor", board.path("config").path("flowName").asString(), "the first template seeds the flow");

        int before = calls.size();
        JsonNode s = seed(id(board), "Lånecyklar");
        assertEquals("Lånecyklar", s.path("title").asString());
        engine.idle();
        assertEquals(before + 1, calls.size(), "a short seed needs no LLM call, only the judge");
        assertTrue(lastCall("judgement").user().contains("Färre bilresor i staden"), "the judge sees the goal");
        Idea judged = repo.getIdea(id(s));
        assertEquals(3, judged.originality());
        assertEquals(List.of("public space", "sound"), judged.keywords(), "keywords are normalised");

        JsonNode e = api("POST", "/api/ideas/" + id(s) + "/expand", Map.of()).data();
        assertEquals(3, e.path("ideas").size());
        assertTrue(e.path("ideas").path(0).path("title").asString().matches("^Barn \\d+\\.1$"), "quotes and trailing period are stripped");
        assertTrue(lastCall("expand").user().contains("Every idea must serve this goal"));
        assertTrue(lastCall("expand").user().contains("- Lånecyklar"), "existing titles reach the prompt");
        engine.idle();
        for (JsonNode i : e.path("ideas")) assertNotNull(repo.getIdea(id(i)).judgedAt(), "children are judged in the background");

        long c1 = id(e.path("ideas").path(0)), c2 = id(e.path("ideas").path(1));
        JsonNode c = api("POST", "/api/boards/" + id(board) + "/combine", Map.of("a", c1, "b", c2)).data();
        assertEquals(2, c.path("idea").path("generation").asInt());
        engine.idle();
        judgeScores = new int[]{9, 0, 4}; // out of range on purpose
        JsonNode j = api("POST", "/api/ideas/" + id(c.path("idea")) + "/judge", Map.of()).data().path("idea");
        assertEquals(List.of(5, 1, 4), List.of(j.path("originality").asInt(), j.path("feasibility").asInt(), j.path("impact").asInt()), "scores are clamped");

        Res md = api("GET", "/api/boards/" + id(board) + "/export.md?lang=sv");
        assertTrue(md.type().startsWith("text/markdown"), md.type());
        assertTrue(md.text().startsWith("# Ny tavla"));
        assertTrue(md.text().contains("> Färre bilresor i staden"));
        assertTrue(md.text().contains("## 🥉 Korsning — 10/15"), "10/15 is bronze");
        assertTrue(md.text().contains("`public space` `sound`"));
        assertTrue(repo.spentOn(id(board)) > 0, "spend lands on the board");
        judgeScores = new int[]{3, 3, 3};
    }

    @Test
    @Order(2)
    void eachRoleUsesItsOwnModelOverriddenPerBoard() {
        ObjectNode pushed = Js.tree(runtime.get());
        ((ObjectNode) pushed.path("models")).put("expand", "ollama:qwen3:8b");
        pushed.putObject("keys").put("openrouter", "sk-or-secret-123");
        Res r = api("PUT", "/api/runtime-settings", pushed);
        assertFalse(r.text().contains("sk-or-secret-123"), "keys are never echoed back");
        JsonNode openrouter = Js.arr(r.data().path("providers")).stream().filter(p -> p.path("id").asString().equals("openrouter")).findFirst().orElseThrow();
        assertEquals("browser", openrouter.path("keySource").asString());
        assertTrue(r.data().path("settingsReceived").asBoolean());

        JsonNode board = newBoard();
        JsonNode s = seed(id(board), "Rot");
        api("POST", "/api/ideas/" + id(s) + "/expand", Map.of());
        assertEquals("ollama:qwen3:8b", lastCall("expand").model());

        api("PATCH", "/api/boards/" + id(board), Js.parse("{\"config\":{\"models\":{\"expand\":\"anthropic:claude-opus-5-5\"}}}"));
        api("POST", "/api/ideas/" + id(s) + "/expand", Map.of());
        assertEquals("anthropic:claude-opus-5-5", lastCall("expand").model(), "board override wins");
        api("PATCH", "/api/boards/" + id(board), Js.parse("{\"config\":{\"models\":{\"expand\":\"\"}}}"));
        api("POST", "/api/ideas/" + id(s) + "/expand", Map.of());
        assertEquals("ollama:qwen3:8b", lastCall("expand").model(), "an empty override falls back to the app setting");
        engine.idle();
        runtime.set(testSettings());
    }

    @Test
    @Order(3)
    void boardConfigIsValidatedAndClamped() {
        JsonNode board = newBoard();
        JsonNode c = api("PATCH", "/api/boards/" + id(board), Js.parse("""
                {"config":{"mode":"chaos","rate":{"stepsPerHour":0,"usdPerDay":-3},"autopilotSeconds":1,"flow":{"system":""},"lang":"de"}}"""))
                .data().path("board").path("config");
        assertEquals("manual", c.path("mode").asString());
        assertEquals(1, c.path("rate").path("stepsPerHour").asInt());
        assertEquals(0, c.path("rate").path("usdPerDay").asDouble());
        assertEquals(3, c.path("autopilotSeconds").asInt());
        assertTrue(c.path("flow").path("system").asString().length() > 20, "an empty prompt keeps the old one");
        assertEquals("sv", c.path("lang").asString());
    }

    @Test
    @Order(4)
    void theJudgeTurnsGoldIntoAClaudeCodeProject() throws IOException {
        judgeScores = new int[]{5, 4, 5};
        JsonNode board = newBoard("{\"goal\":\"Ljud i offentliga rum\"}");
        JsonNode s = seed(id(board), "Sjungande parkbänk");
        engine.idle();
        Idea gold = repo.getIdea(id(s));
        assertNotNull(gold.projectPath(), "gold -> project");
        assertTrue(gold.projectPath().startsWith(projectsRoot.toString()));
        assertTrue(gold.projectPath().endsWith("sjungande-bank"), "slug is ASCII: " + gold.projectPath());
        String claude = Files.readString(Path.of(gold.projectPath(), "CLAUDE.md"));
        assertTrue(claude.startsWith("# Sjungande bänk"));
        assertTrue(claude.contains("Ljud i offentliga rum"));
        assertTrue(claude.contains("14/15 🥇"));
        assertTrue(Files.exists(Path.of(gold.projectPath(), "vault", "Home.md")));
        assertTrue(has(Files.readString(Path.of(gold.projectPath(), ".gitignore")), "^vault/$"));

        assertTrue(api("POST", "/api/ideas/" + id(s) + "/project", Map.of()).data().path("existed").asBoolean(), "one project per idea");
        api("POST", "/api/ideas/" + id(s) + "/reveal", Map.of());
        assertEquals(List.of(gold.projectPath()), revealed);

        JsonNode off = newBoard();
        api("PATCH", "/api/boards/" + id(off), Js.parse("{\"config\":{\"autoProjectOnGold\":false}}"));
        JsonNode s2 = seed(id(off), "Ännu en guldidé");
        engine.idle();
        assertNull(repo.getIdea(id(s2)).projectPath());
        judgeScores = new int[]{3, 3, 3};
    }

    @Test
    @Order(5)
    void announceGuestsCrossBreedOnOtherBoardsJudgedAgainstTheirGoals() {
        JsonNode a = newBoard("{\"title\":\"Ljud\",\"goal\":\"Ljudkonst\"}");
        JsonNode b = newBoard("{\"title\":\"Trafik\",\"goal\":\"Färre bilar\"}");
        JsonNode c = newBoard("{\"title\":\"Mat\",\"goal\":\"Mindre matsvinn\"}");
        JsonNode idea = seed(id(a), "Visslande trafikljus");
        engine.idle();

        JsonNode data = api("POST", "/api/ideas/" + id(idea) + "/announce", Map.of("boardIds", List.of(id(a), id(b), id(c), id(b)))).data();
        assertEquals(List.of(id(b), id(c)), Js.arr(data.path("guests")).stream().map(g -> g.path("boardId").asLong()).sorted().toList(),
                "not to its own board, once per board");
        JsonNode guest = Js.arr(data.path("guests")).stream().filter(g -> g.path("boardId").asLong() == id(b)).findFirst().orElseThrow().path("idea");
        assertEquals("guest", guest.path("kind").asString());
        assertEquals("Ljud", guest.path("originBoardTitle").asString());
        engine.idle();
        assertTrue(calls.stream().anyMatch(x -> x.schemaName().equals("judgement") && x.user().contains("Visslande trafikljus") && x.user().contains("Färre bilar")),
                "judged against the target board's goal");

        JsonNode again = api("POST", "/api/ideas/" + id(idea) + "/announce", Map.of("boardIds", "all")).data();
        assertTrue(Js.arr(again.path("guests")).stream().noneMatch(g -> List.of(id(b), id(c)).contains(g.path("boardId").asLong())), "never twice on the same board");
        List<Long> skipped = Js.arr(again.path("skipped")).stream().map(JsonNode::asLong).toList();
        assertTrue(skipped.containsAll(List.of(id(b), id(c))));
        JsonNode onward = api("POST", "/api/ideas/" + id(guest) + "/announce", Map.of("boardIds", List.of(id(a)))).data();
        assertEquals(0, onward.path("guests").size(), "a guest cannot be announced back to its original board");

        JsonNode local = seed(id(b), "Cykelfiler");
        JsonNode child = api("POST", "/api/boards/" + id(b) + "/combine", Map.of("a", id(guest), "b", id(local))).data();
        List<Idea> lineage = repo.lineage(id(child.path("idea")));
        assertTrue(lineage.stream().anyMatch(i -> i.title().equals("Visslande trafikljus")) && lineage.stream().anyMatch(i -> i.title().equals("Cykelfiler")));
        assertTrue(lineage.stream().anyMatch(i -> i.boardId() == id(a)), "lineage follows the guest home");
        engine.idle();
    }

    @Test
    @Order(6)
    void spinOffANewBoardGrownFromOneIdea() {
        JsonNode parent = newBoard("{\"title\":\"Mor\",\"goal\":\"Allt\"}");
        api("PATCH", "/api/boards/" + id(parent), Js.parse("{\"config\":{\"judgeGuidance\":\"Belöna lokalt.\",\"mode\":\"auto\"}}"));
        JsonNode idea = seed(id(parent), "Ljudande busshållplats");
        JsonNode data = api("POST", "/api/ideas/" + id(idea) + "/spinoff", Map.of()).data();
        assertEquals("Ljudande busshållplats", data.path("board").path("goal").asString());
        assertEquals("Belöna lokalt.", data.path("board").path("config").path("judgeGuidance").asString(), "config is copied");
        assertEquals("manual", data.path("board").path("config").path("mode").asString(), "but it starts manual");
        assertEquals("Mor", data.path("board").path("originBoardTitle").asString());
        assertEquals(id(idea), data.path("idea").path("originIdeaId").asLong());
        JsonNode p = api("GET", "/api/boards/" + id(parent)).data();
        assertEquals(Js.parse("[{\"ideaId\":" + id(idea) + ",\"boardId\":" + id(data.path("board")) + ",\"title\":\"Ljudande busshållplats\"}]"),
                p.path("spinoffs"));
        api("PATCH", "/api/boards/" + id(parent), Js.parse("{\"config\":{\"mode\":\"manual\"}}"));
        engine.idle();
    }

    @Test
    @Order(7)
    void leadsXKeepsOnlySearchCitedPostsResearchOnlyRealPaperIds() {
        JsonNode board = newBoard("{\"goal\":\"Interaktivt ljus\"}");
        xCitations = List.of("https://x.com/a/status/1", "https://x.com/b/status/2");
        JsonNode x = api("POST", "/api/boards/" + id(board) + "/leads/x", Map.of()).data();
        assertEquals(List.of("Trend A", "Trend B"), texts(x.path("leads"), "title"));
        assertEquals(1, x.path("dropped").asInt(), "the uncited post is dropped");
        assertEquals(Js.parse("[{\"id\":\"web\",\"engine\":\"native\",\"max_results\":12}]"), lastCall("leads").plugins());
        assertEquals("openrouter:x-ai/grok-4.3", lastCall("leads").model());

        JsonNode r = api("POST", "/api/boards/" + id(board) + "/leads/research", Map.of()).data();
        assertEquals(List.of("https://huggingface.co/papers/2610.1"), texts(r.path("leads"), "url"));
        assertTrue(lastCall("research").user().contains("Interaktivt ljus"));

        JsonNode s = api("POST", "/api/leads/" + id(x.path("leads").path(0)) + "/seed", Map.of()).data();
        assertEquals("https://x.com/a/status/1", s.path("idea").path("sourceUrl").asString());
        assertEquals(id(s.path("idea")), s.path("lead").path("ideaId").asLong());

        xCitations = List.of();
        JsonNode x2 = api("POST", "/api/boards/" + id(board) + "/leads/x", Map.of()).data();
        assertTrue(x2.path("empty").asBoolean());
        assertEquals(2, x2.path("leads").size(), "nothing verified: old leads stay");
        engine.idle();
    }

    @Test
    @Order(8)
    void summaryOfABoardKeptOnTheBoardAndInTheExport() {
        JsonNode board = newBoard();
        assertEquals(400, api("POST", "/api/boards/" + id(board) + "/summary", Map.of()).status(), "needs two ideas");
        seed(id(board), "Lånecyklar");
        seed(id(board), "Korsning");
        engine.idle();
        JsonNode data = api("POST", "/api/boards/" + id(board) + "/summary", Map.of()).data();
        assertEquals("Tavlan rör sig mot ljud.", data.path("board").path("summary").path("headline").asString());
        assertEquals(2, data.path("board").path("summary").path("ideaCount").asInt());
        String md = api("GET", "/api/boards/" + id(board) + "/export.md").text();
        assertTrue(md.contains("## Sammanfattning\n\nTavlan rör sig mot ljud."), md);
    }

    @Test
    @Order(9)
    void dashboardKeywordTrendsAcrossBoardsAndDrillDown() {
        judgeKeywords = List.of("Trend Keyword");
        JsonNode b1 = newBoard("{\"title\":\"D1\"}");
        JsonNode b2 = newBoard("{\"title\":\"D2\"}");
        seed(id(b1), "Ett");
        seed(id(b2), "Två");
        engine.idle();
        JsonNode data = api("GET", "/api/dashboard?days=7").data();
        JsonNode kw = Js.arr(data.path("keywords")).stream().filter(k -> k.path("keyword").asString().equals("trend keyword")).findFirst().orElseThrow();
        assertEquals(2, kw.path("count").asInt());
        assertEquals(2, kw.path("boards").asInt());
        assertEquals(2, kw.path("recent").asInt());
        assertEquals(7, kw.path("series").size());
        assertEquals(2, kw.path("series").path(6).asInt(), "today");
        JsonNode drill = api("GET", "/api/keywords/" + URLEncoder.encode("trend keyword", StandardCharsets.UTF_8).replace("+", "%20") + "/ideas").data();
        assertEquals(List.of("D1", "D2"), texts(drill.path("ideas"), "boardTitle").stream().sorted().toList());
        judgeKeywords = List.of("public space", "sound");
    }

    @Test
    @Order(10)
    void liveEventsABrowserFollowingABoardSeesWhatHappens() throws Exception {
        JsonNode board = newBoard();
        HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/boards/" + id(board) + "/events")).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, res.statusCode());
        assertTrue(res.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"));
        List<JsonNode> seen = new CopyOnWriteArrayList<>();
        List<String> rawLines = new CopyOnWriteArrayList<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                    rawLines.add(line);
                    if (line.startsWith("data: ")) seen.add(Js.parse(line.substring(6)));
                }
            } catch (IOException closed) {
                // stream closed by the test
            }
        });
        seed(id(board), "Live");
        engine.idle();
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && !(seen.stream().anyMatch(e -> e.path("type").asString().equals("ideas")) && seen.stream().anyMatch(e -> e.path("type").asString().equals("judging")))) {
            Thread.sleep(20);
        }
        res.body().close();
        reader.join(2000);
        List<String> types = seen.stream().map(e -> e.path("type").asString()).toList();
        assertEquals("hello", types.getFirst());
        assertTrue(seen.getFirst().has("autopilot") && seen.getFirst().has("settingsReceived"));
        assertTrue(types.contains("ideas") && types.contains("judging"), String.join(",", types));
        assertTrue(rawLines.getFirst().startsWith("data: {"), "upstream framing: 'data: <json>'");
        assertEquals(404, api("GET", "/api/boards/999999/events").status());
    }

    @Test
    @Order(11)
    void providerFailuresSurfaceWithTheirCodeAndNothingIsHalfSaved() {
        JsonNode board = newBoard();
        JsonNode s = seed(id(board), "Frö");
        engine.idle();
        failNext.set(new ProviderError("no_key", "OpenRouter: no API key"));
        Res r = api("POST", "/api/ideas/" + id(s) + "/expand", Map.of());
        assertEquals(502, r.status());
        assertEquals("no_key", r.data().path("code").asString());
        assertEquals("OpenRouter: no API key", r.data().path("error").asString());
        assertEquals(1, repo.listIdeas(id(board)).size());
    }

    @Test
    @Order(12)
    void linksFetchedAndDistilledUnreadableLinksAreAnError() {
        JsonNode board = newBoard();
        Res ok = api("POST", "/api/boards/" + id(board) + "/seed", Map.of("text", "https://example.com/artikel"));
        assertEquals("Destillerat frö", ok.data().path("idea").path("title").asString());
        assertEquals("url_unreadable", api("POST", "/api/boards/" + id(board) + "/seed", Map.of("text", "https://example.com/nej")).data().path("code").asString());
        assertEquals("url_no_text", api("POST", "/api/boards/" + id(board) + "/seed", Map.of("text", "https://example.com/tom")).data().path("code").asString());
        engine.idle();
        assertEquals(1, repo.listIdeas(id(board)).size());
    }

    @Test
    @Order(13)
    void templatesFourDefaultsEditableTheLastOneStays() {
        assertEquals(4, api("GET", "/api/templates").data().path("templates").size());
        assertEquals(400, api("POST", "/api/templates", Map.of("name", "X")).status());
        JsonNode n = api("POST", "/api/templates", Map.of("name", "Ny", "system", "s", "expand", "e", "combine", "c")).data();
        JsonNode board = newBoard("{\"templateId\":" + id(n.path("template")) + "}");
        assertEquals("Ny", board.path("config").path("flowName").asString());
        List<JsonNode> all = Js.arr(api("GET", "/api/templates").data().path("templates"));
        for (JsonNode t : all.subList(0, all.size() - 1)) assertEquals(200, api("DELETE", "/api/templates/" + id(t)).status());
        assertEquals("last_template", api("DELETE", "/api/templates/" + id(all.getLast())).data().path("code").asString());
    }

    @Test
    @Order(14)
    void staticFilesPathTraversalUnknownRoutes() {
        Res js = api("GET", "/js/probe.js");
        assertEquals(200, js.status());
        assertTrue(js.type().contains("javascript"), js.type());
        // Tomcat refuses an encoded dot segment outright (400); upstream answered 404.
        assertTrue(List.of(400, 403, 404).contains(api("GET", "/%2e%2e/package.json").status()));
        assertEquals(405, api("POST", "/js/probe.js", Map.of()).status(), "outside /api only GET and HEAD");
        Res nope = api("GET", "/api/nope");
        assertEquals(404, nope.status());
        assertEquals(Js.parse("{\"error\":\"No route for GET /api/nope\",\"code\":\"not_found\"}"), nope.data());
        assertEquals("bad_json", api("POST", "/api/boards", "{nope").data().path("code").asString());
        assertEquals(404, api("GET", "/api/boards/abc").status());
    }

    @Test
    @Order(15)
    void reportTotalsProjectsPerBoardInsightsSavedConclusionsMarkdown() {
        JsonNode before = api("GET", "/api/report?days=30").data();
        assertTrue(before.path("totals").path("ideas").asInt() > 0 && before.path("totals").path("boards").asInt() > 0);
        assertTrue(texts(before.path("projects"), "title").contains("Sjungande parkbänk"), "the gold project from earlier is listed");
        assertTrue(Js.arr(before.path("boards")).stream().allMatch(b -> b.path("best").isArray() && b.path("best").size() <= 3));
        JsonNode withSummary = Js.arr(before.path("boards")).stream().filter(b -> !Js.absent(b.path("summary"))).findFirst().orElseThrow();
        assertTrue(withSummary.path("summary").path("stale").isBoolean());
        assertTrue(before.path("synthesis").isNull());

        JsonNode after = api("POST", "/api/report/synthesis", Map.of("days", 30, "lang", "sv")).data();
        ChatRequest call = lastCall("synthesis");
        assertTrue(call.user().contains("Sjungande parkbänk"), "the synthesis sees the boards' best ideas");
        assertTrue(call.user().contains("Claude Code projects:"));
        assertEquals(AppSettings.defaults().models().get("summary"), call.model());
        assertTrue(after.path("synthesis").path("headline").isString());
        assertEquals(30, after.path("synthesis").path("days").asInt());

        String md = api("GET", "/api/report.md?days=30&lang=sv").text();
        assertTrue(has(md, "^# Rapport · \\d{4}-\\d{2}-\\d{2}"));
        assertTrue(md.contains("## Slutsatser"));
        assertTrue(has(md, "cd \".*sjungande-bank\" && claude"));
    }
}
