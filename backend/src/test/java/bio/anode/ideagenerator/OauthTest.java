package bio.anode.ideagenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import bio.anode.ideagenerator.Providers.ChatRequest;
import bio.anode.ideagenerator.Providers.ChatResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/** ideagen.auth=oauth: login required, every tenant sees only its own data, secrets never leave, a confined filesystem. */
@SpringBootTest(properties = {"ideagen.auth=oauth", "idea.autopilot=false",
        "spring.security.oauth2.client.registration.github.client-id=test-client",
        "spring.security.oauth2.client.registration.github.client-secret=test-secret"})
class OauthTest {
    static final Path dataDir;
    static final Path keyset;
    static final List<ChatRequest> calls = new CopyOnWriteArrayList<>();
    static volatile int[] scores = {3, 3, 3};

    static {
        try {
            dataDir = Files.createTempDirectory("ideagen-data-");
            keyset = Files.createTempDirectory("ideagen-keys-").resolve("keyset.json");
            Secrets.generate(keyset);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestDb.register(r);
        r.add("ideagen.keyset-file", keyset::toString);
        r.add("ideagen.data-dir", dataDir::toString);
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        Engine.Io io() {
            Providers.Chat chat = r -> {
                calls.add(r);
                String v = switch (r.schemaName()) {
                    case "judgement" -> "{\"reason\":\"r\",\"originality\":" + scores[0] + ",\"feasibility\":" + scores[1] + ",\"impact\":" + scores[2]
                            + ",\"keywords\":[\"secret keyword\"]}";
                    case "brief" -> """
                            {"name":"Guldet","slug":"guldet","oneLiner":"o","problem":"p","audience":"a","mvp":["m"],"outOfScope":["o"],
                             "stack":"s","firstSteps":["f"],"openQuestions":["q"],"risks":["r"]}""";
                    default -> "{\"title\":\"T\",\"pitch\":\"P\"}";
                };
                return new ChatResult(Js.parse(v), 0.001);
            };
            // The real shared-server page fetch, so the private-address guard is what is tested.
            return new Engine.Io(s -> chat, url -> Extract.fetchPage(url, true), g -> List.of(), p -> {
                throw new AssertionError("reveal on a shared server");
            }, false, q -> List.of());
        }
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    Repo repo;
    @Autowired
    Engine engine;
    MockMvc mvc;
    /** Every response body, to check that no secret ever leaves. */
    final List<String> seen = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    static RequestPostProcessor as(String tenant) {
        return oauth2Login().oauth2User(new Security.TenantUser(tenant, "test", new DefaultOAuth2User(List.of(), Map.of("id", 1), "id")));
    }

    MockHttpServletResponse call(MockHttpServletRequestBuilder req, int status) throws Exception {
        var result = mvc.perform(req).andReturn();
        // A streamed body (the zip) completes on an async dispatch.
        MockHttpServletResponse res = result.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(result)).andReturn().getResponse() : result.getResponse();
        if (res.getContentType() == null || !res.getContentType().contains("zip")) seen.add(res.getContentAsString());
        assertEquals(status, res.getStatus(), req + " -> " + res.getContentAsString());
        return res;
    }

    JsonNode json(MockHttpServletRequestBuilder req, int status) throws Exception {
        String body = call(req, status).getContentAsString();
        return body.isEmpty() ? Js.object() : Js.parse(body);
    }

    static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder b, Object body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : Js.stringify(body));
    }

    /** A signed-in write: session principal and CSRF token. */
    static MockHttpServletRequestBuilder w(MockHttpServletRequestBuilder b, String tenant, Object payload) {
        return body(b, payload == null ? Map.of() : payload).with(as(tenant)).with(csrf());
    }

    String tenant(String name) {
        return repo.login("github", "id-" + name + "-" + System.nanoTime(), name, null);
    }

    @Test
    void withoutLoginTheApiAnswers401AndStaticFilesStayPublic() throws Exception {
        JsonNode r = json(get("/api/boards"), 401);
        assertEquals("unauthenticated", r.path("code").asString());
        assertEquals(List.of("github"), Js.arr(r.path("login")).stream().map(JsonNode::asString).toList(), "only configured providers");
        json(get("/api/me"), 401);
        json(get("/api/settings"), 401);
        json(get("/api/boards/1/events"), 401);
        json(body(post("/api/boards"), Map.of()).with(csrf()), 401);
        call(get("/js/probe.js"), 200);
        assertEquals(302, mvc.perform(get("/oauth2/authorization/github")).andReturn().getResponse().getStatus(), "login starts at GitHub");
        String google = mvc.perform(get("/oauth2/authorization/google")).andReturn().getResponse().getRedirectedUrl();
        assertTrue(google == null || !google.contains("accounts.google.com"), "an unconfigured provider has no login route: " + google);
    }

    @Test
    void aLoginGetsItsOwnTenantOnceWithTheDefaultTemplates() throws Exception {
        String a = repo.login("github", "4242", "alice", "a@example.com");
        assertEquals(a, repo.login("github", "4242", "alice", "a@example.com"), "same identity, same tenant");
        assertNotEquals(a, repo.login("google", "4242", "alice", "a@example.com"), "no merging by email or id across providers");
        assertEquals(4, repo.listTemplates(a).size());
        JsonNode me = json(get("/api/me").with(as(a)), 200);
        assertEquals("oauth", me.path("mode").asString());
        assertEquals("alice", me.path("name").asString());
    }

    @Test
    void writesNeedTheCsrfTokenAndLogoutEndsTheSession() throws Exception {
        String a = tenant("csrf");
        assertEquals("forbidden", json(body(post("/api/boards"), Map.of()).with(as(a)), 403).path("code").asString());
        json(w(post("/api/boards"), a, Map.of("title", "ok")), 200);
        call(post("/api/logout").with(as(a)).with(csrf()), 204);
    }

    @Test
    void twoTenantsCannotSeeOrTouchEachOthersData() throws Exception {
        String a = tenant("alice"), b = tenant("bob");
        long board = json(w(post("/api/boards"), a, Map.of("title", "A-board", "goal", "A-goal")), 200).path("board").path("id").asLong();
        long idea = json(w(post("/api/boards/" + board + "/seed"), a, Map.of("text", "A-idea")), 200).path("idea").path("id").asLong();
        long idea2 = json(w(post("/api/boards/" + board + "/seed"), a, Map.of("text", "A-idea-2")), 200).path("idea").path("id").asLong();
        engine.idle();
        long template = json(w(post("/api/templates"), a, Map.of("name", "A-template", "system", "s", "expand", "e", "combine", "c")), 200)
                .path("template").path("id").asLong();
        long lead = repo.replaceLeads(a, board, "research", List.of(new Repo.LeadDraft("A-lead", "s", "h", "https://example.com", Map.of())))
                .getFirst().id();
        json(w(put("/api/settings"), a, Map.of("keys", Map.of("anthropic", "sk-A-secret"), "scientistKey", "ais_A_secret")), 200);
        json(w(put("/api/settings"), b, Map.of("keys", Map.of("openrouter", "sk-B-secret"))), 200);

        // Another tenant's board, idea, lead, template: not found, never forbidden.
        for (String path : List.of("/api/boards/" + board, "/api/boards/" + board + "/export.md", "/api/boards/" + board + "/events",
                "/api/ideas/" + idea + "/project.zip")) {
            json(get(path).with(as(b)), 404);
        }
        json(w(patch("/api/boards/" + board), b, Map.of("title", "pwned")), 404);
        json(w(put("/api/boards/" + board + "/positions"), b, Map.of("positions", List.of(Map.of("id", idea, "x", 1, "y", 1)))), 404);
        for (String path : List.of("/seed", "/combine", "/duel", "/summary", "/leads/x", "/leads/research")) {
            json(w(post("/api/boards/" + board + path), b, Map.of("text", "x", "a", idea, "b", idea2)), 404);
        }
        for (String path : List.of("/expand", "/judge", "/project", "/reveal", "/announce", "/scientist", "/spinoff")) {
            json(w(post("/api/ideas/" + idea + path), b, Map.of("boardIds", "all")), 404);
        }
        json(w(patch("/api/ideas/" + idea), b, Map.of("medalOverride", 3)), 404);
        json(w(post("/api/leads/" + lead + "/seed"), b, null), 404);
        json(w(put("/api/templates/" + template), b, Map.of("name", "x", "system", "s", "expand", "e", "combine", "c")), 404);
        json(w(delete("/api/templates/" + template), b, null), 404);
        assertFalse(json(w(delete("/api/ideas/" + idea), b, null), 200).path("ok").asBoolean());
        assertFalse(json(w(delete("/api/leads/" + lead), b, null), 200).path("ok").asBoolean());
        assertFalse(json(w(delete("/api/boards/" + board), b, null), 200).path("ok").asBoolean());

        // Lists, dashboard, keywords, report: only one's own.
        assertEquals(0, json(get("/api/boards").with(as(b)), 200).path("boards").size());
        assertTrue(json(get("/api/templates").with(as(b)), 200).path("templates").toString().indexOf("A-template") < 0);
        assertEquals(0, json(get("/api/dashboard").with(as(b)), 200).path("totals").path("ideas").asInt());
        assertEquals(0, json(get("/api/keywords/secret keyword/ideas").with(as(b)), 200).path("ideas").size());
        assertEquals(0, json(get("/api/report").with(as(b)), 200).path("totals").path("boards").asInt());
        assertFalse(call(get("/api/report.md").with(as(b)), 200).getContentAsString().contains("A-board"));
        assertFalse(json(get("/api/settings").with(as(b)), 200).path("settings").path("keys").path("anthropic").path("stored").asBoolean());

        // Ids of the other tenant inside one's own requests do nothing either.
        long own = json(w(post("/api/boards"), b, Map.of("title", "B-board", "templateId", template)), 200).path("board").path("id").asLong();
        assertFalse(json(get("/api/boards/" + own).with(as(b)), 200).path("board").path("config").path("flowName").asString().equals("A-template"));
        json(w(post("/api/boards/" + own + "/combine"), b, Map.of("a", idea, "b", idea2)), 404);
        long bIdea = json(w(post("/api/boards/" + own + "/seed"), b, Map.of("text", "B-idea")), 200).path("idea").path("id").asLong();
        JsonNode announced = json(w(post("/api/ideas/" + bIdea + "/announce"), b, Map.of("boardIds", List.of(board))), 200);
        assertEquals(0, announced.path("guests").size());
        assertEquals(List.of(board), Js.arr(announced.path("skipped")).stream().map(JsonNode::asLong).toList());
        engine.idle();

        // Alice's data is untouched, and her dashboard and report have it.
        JsonNode mine = json(get("/api/boards/" + board).with(as(a)), 200);
        assertEquals("A-board", mine.path("board").path("title").asString());
        assertEquals(2, mine.path("ideas").size());
        assertEquals(1, mine.path("leads").size());
        assertEquals(2, json(get("/api/dashboard").with(as(a)), 200).path("totals").path("ideas").asInt());
        assertEquals(1, json(get("/api/report").with(as(a)), 200).path("totals").path("boards").asInt());

        for (String body : seen) {
            assertFalse(body.contains("sk-A-secret") || body.contains("sk-B-secret") || body.contains("ais_A_secret"), body);
            assertFalse(body.contains("tenantId") || body.contains("tenant_id"), "owners stay server side: " + body);
        }
    }

    @Test
    void aSharedServerUsesOnlyStoredKeysPublicAddressesAndTheTenantsOwnFolder() throws Exception {
        String a = tenant("carol"), b = tenant("dave");
        Function<String, String> env = Providers.env;
        Providers.env = k -> "from-the-environment";
        try {
            JsonNode providers = json(get("/api/providers").with(as(a)), 200).path("providers");
            assertTrue(Js.arr(providers).stream().allMatch(p -> p.path("keySource").isNull()), "no environment keys on a shared server");
        } finally {
            Providers.env = env;
        }

        JsonNode s = json(w(put("/api/settings"), a, Map.of("projectsRoot", "/etc", "workspaceRoot", "/tmp/ws", "reposRoots", "/",
                "baseUrls", Map.of("custom", "http://127.0.0.1:9"), "scientistUrl", "http://169.254.169.254", "scientistKey", "ais_x")), 200)
                .path("settings");
        assertFalse(s.has("projectsRoot") || s.has("workspaceRoot") || s.has("reposRoots"), "folder settings do not exist here");
        assertEquals("private_address", json(w(post("/api/providers/custom/models"), a, null), 502).path("code").asString());
        long board = json(w(post("/api/boards"), a, Map.of("title", "Gold")), 200).path("board").path("id").asLong();
        assertEquals("url_unreadable", json(w(post("/api/boards/" + board + "/seed"), a, Map.of("text", "http://127.0.0.1:8300/api/settings")), 422)
                .path("code").asString());
        long plain = json(w(post("/api/boards/" + board + "/seed"), a, Map.of("text", "Vanlig")), 200).path("idea").path("id").asLong();
        engine.idle();
        assertEquals("private_address", json(w(post("/api/ideas/" + plain + "/scientist"), a, null), 400).path("code").asString());

        scores = new int[]{5, 4, 5};
        long gold;
        try {
            gold = json(w(post("/api/boards/" + board + "/seed"), a, Map.of("text", "Guldidé")), 200).path("idea").path("id").asLong();
            engine.idle();
        } finally {
            scores = new int[]{3, 3, 3};
        }
        String path = repo.getIdea(a, gold).projectPath();
        assertEquals("guldet", path, "only the folder name: no server path in the JSON");
        assertTrue(Files.isDirectory(dataDir.resolve("tenants").resolve(a).resolve("projects").resolve(path)));
        assertFalse(call(get("/api/boards/" + board).with(as(a)), 200).getContentAsString().contains(dataDir.toString()), "no server path anywhere");
        assertFalse(Files.exists(Path.of("/tmp/ws/INDEX.md")), "no workspace filing");
        assertTrue(calls.stream().noneMatch(c -> c.schemaName().equals("workspace_match")));

        MockHttpServletResponse zip = call(get("/api/ideas/" + gold + "/project.zip").with(as(a)), 200);
        assertEquals("application/zip", zip.getContentType());
        assertTrue(zip.getHeader("Content-Disposition").contains("guldet.zip"));
        List<String> names = entries(zip.getContentAsByteArray());
        assertTrue(names.contains("guldet/CLAUDE.md") && names.contains("guldet/vault/Home.md"), names.toString());
        json(get("/api/ideas/" + gold + "/project.zip").with(as(b)), 404);
        assertEquals("not_available", json(w(post("/api/ideas/" + gold + "/reveal"), a, null), 403).path("code").asString());
    }

    static List<String> entries(byte[] zip) throws IOException {
        List<String> out = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) out.add(e.getName());
        }
        return out;
    }
}
