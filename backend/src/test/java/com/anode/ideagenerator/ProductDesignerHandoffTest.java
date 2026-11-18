package com.anode.ideagenerator;

import static com.anode.ideagenerator.repository.TenantRepository.LOCAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anode.ideagenerator.Providers.ChatResult;
import com.anode.ideagenerator.model.Board;
import com.anode.ideagenerator.model.Idea;
import com.anode.ideagenerator.model.NewIdea;
import com.anode.ideagenerator.repository.BoardRepository;
import com.anode.ideagenerator.repository.IdeaRepository;
import com.anode.ideagenerator.repository.ResearchRepository;
import com.anode.ideagenerator.service.Ai;
import com.anode.ideagenerator.service.ProductDesignerService;
import com.anode.ideagenerator.service.ProjectService;
import com.anode.ideagenerator.service.SettingsService;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/** "Send to productDesigner": the idea.json (validated against idea-brief.schema.json) reaches a fake productDesigner with the pd_ key. */
@SpringBootTest(properties = {"idea.autopilot=false", "idea.research.poll-ms=3600000", "ideagen.public-url=https://ideas.example.org/"})
class ProductDesignerHandoffTest {
    static HttpServer designer;
    static final List<String> received = new CopyOnWriteArrayList<>();
    static volatile int status = 201;
    static volatile String answer = "{\"id\":7,\"nom\":\"Ledger\",\"questions\":1,\"brouillard\":3,\"horsPerimetre\":1}";
    static final AtomicInteger briefCalls = new AtomicInteger();

    @BeforeAll
    static void start() throws Exception {
        designer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        designer.createContext("/api/projets/import", ex -> {
            received.add(ex.getRequestMethod() + " " + ex.getRequestURI() + "\n" + ex.getRequestHeaders().getFirst("Authorization") + "\n"
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", status < 400 ? "application/json" : "application/problem+json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        designer.start();
    }

    @AfterAll
    static void stop() {
        designer.stop(0);
    }

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        TestDb.register(r);
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        Ai.Io io() {
            Providers.Chat chat = r -> {
                if (r.schemaName().equals("brief")) briefCalls.incrementAndGet();
                return new ChatResult(Js.parse(r.schemaName().equals("brief") ? """
                        {"name":"Ledger","slug":"ledger","oneLiner":"Know your reagent stock.","problem":"Labs run out mid-experiment.","audience":"Lab managers",
                         "mvp":["Scan in/out"],"outOfScope":["Purchasing"],"stack":"Spring, Postgres","firstSteps":["Interview"],
                         "openQuestions":["Which barcodes?"],"risks":["Nobody scans"]}"""
                        : "{\"reason\":\"r\",\"originality\":3,\"feasibility\":3,\"impact\":3,\"keywords\":[\"lab\"]}"), 0.001);
            };
            return new Ai.Io(s -> chat, url -> { throw new AssertionError("no fetch"); }, g -> List.of(), p -> { }, false, q -> List.of());
        }
    }

    @Autowired SettingsService settings;
    @Autowired BoardRepository boards;
    @Autowired IdeaRepository ideas;
    @Autowired ResearchRepository research;
    @Autowired ProductDesignerService handoff;
    @Autowired ProjectService projects;

    Idea idea;

    @BeforeEach
    void setUp() throws Exception {
        received.clear();
        status = 201;
        answer = "{\"id\":7,\"nom\":\"Ledger\",\"questions\":1,\"brouillard\":3,\"horsPerimetre\":1}";
        briefCalls.set(0);
        settings.save(LOCAL, Js.parse("{\"productDesignerUrl\":\"http://127.0.0.1:" + designer.getAddress().getPort() + "\",\"productDesignerKey\":\"pd_test_key\","
                + "\"projectsRoot\":" + Js.stringify(Files.createTempDirectory("ideagen-projects-").toString()) + "}"));
        Board board = boards.insert(LOCAL, "Labs", "Help small labs track reagent stock", Board.defaultConfig(), null);
        idea = ideas.insert(LOCAL, new NewIdea(board.id(), "Barcode ledger", "Scan a bottle in and out.", "seed", "https://example.org/forum/881", 0, 0, 0));
    }

    private static JsonNode body(String request) {
        return Js.parse(request.substring(request.indexOf("\n", request.indexOf("\n") + 1) + 1));
    }

    @Test
    void theIdeaJsonReachesProductDesignerWithTheKeyAndMatchesTheContract() throws Exception {
        var result = handoff.send(LOCAL, idea.id());

        assertEquals(7, Js.parse(Js.stringify(result)).path("project").path("id").asInt());
        String request = received.getFirst();
        assertTrue(request.startsWith("POST /api/projets/import?source=https%3A%2F%2Fideas.example.org%2Fboards%2F" + idea.boardId() + "%2Fideas%2F" + idea.id() + "\n"), request);
        assertEquals("Bearer pd_test_key", request.lines().skip(1).findFirst().orElseThrow());

        JsonNode doc = body(request);
        assertEquals("Ledger", doc.path("brief").path("name").asString());
        assertEquals("Help small labs track reagent stock", doc.path("goal").asString());
        assertEquals("Barcode ledger", doc.path("idea").path("title").asString());
        assertTrue(doc.path("research").isMissingNode(), "no research gate yet");
        var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, b -> { })
                .getSchema(Files.readString(Path.of(getClass().getResource("/contracts/idea-brief.schema.json").toURI())), InputFormat.JSON);
        assertEquals(List.of(), schema.validate(Js.stringify(doc), InputFormat.JSON).stream().map(Object::toString).toList(), "idea-brief.schema.json");
    }

    @Test
    void aFinishedResearchGateGoesAlongAndAnExistingProjectsBriefIsNotWrittenTwice() throws Exception {
        research.submitted(LOCAL, idea.id(), "job-1", "https://ais.example.org/v1/jobs/job-1");
        research.done(idea.id(), List.of("Scanning cuts stock-outs."), "LIMS are heavy.", List.of("Scan discipline."));

        projects.create(LOCAL, idea.id());   // writes the project and its idea.json: one brief
        assertEquals(1, briefCalls.get());
        handoff.send(LOCAL, idea.id());
        assertEquals(1, briefCalls.get(), "the project's own idea.json is used");

        JsonNode doc = body(received.getFirst());
        assertEquals(List.of("Scanning cuts stock-outs."), Js.arr(doc.path("research").path("hypotheses")).stream().map(JsonNode::asString).toList());
        assertEquals("https://ais.example.org/v1/jobs/job-1", doc.path("research").path("jobUrl").asString());
        var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, b -> { })
                .getSchema(Files.readString(Path.of(getClass().getResource("/contracts/idea-brief.schema.json").toURI())), InputFormat.JSON);
        assertTrue(schema.validate(Js.stringify(doc), InputFormat.JSON).isEmpty());
    }

    @Test
    void problemsAreReadable() {
        status = 409;
        answer = "{\"title\":\"Conflict\",\"detail\":\"Cette idée est déjà importée : Projet 3\"}";
        AppError conflict = assertThrows(AppError.class, () -> handoff.send(LOCAL, idea.id()));
        assertEquals(409, conflict.status);
        assertEquals("already_imported", conflict.code);
        assertTrue(conflict.getMessage().contains("déjà importée"), conflict.getMessage());

        status = 401;
        answer = "{\"title\":\"Non authentifié\",\"detail\":\"Connexion requise.\"}";
        assertEquals("product_designer_auth", assertThrows(AppError.class, () -> handoff.send(LOCAL, idea.id())).code);

        status = 422;
        answer = "{\"detail\":\"L'idea.json ne respecte pas idea-brief.schema.json\"}";
        assertEquals("product_designer_invalid", assertThrows(AppError.class, () -> handoff.send(LOCAL, idea.id())).code);

        AppError unreachable;
        settings.save(LOCAL, Js.parse("{\"productDesignerUrl\":\"http://127.0.0.1:9\"}"));
        unreachable = assertThrows(AppError.class, () -> handoff.send(LOCAL, idea.id()));
        assertEquals("product_designer_unreachable", unreachable.code);
    }

    @Test
    void withoutAnAddressAndKeyItAsksForThem() {
        settings.save(LOCAL, Js.parse("{\"productDesignerUrl\":\"\",\"productDesignerKey\":\"\"}"));
        AppError e = assertThrows(AppError.class, () -> handoff.send(LOCAL, idea.id()));
        assertEquals(400, e.status);
        assertEquals("no_product_designer", e.code);
        assertFalse(received.stream().anyMatch(r -> r.contains("import")));
    }

    @Test
    void theKeyIsStoredEncryptedAndNeverShown() {
        var view = settings.view(LOCAL);
        assertEquals(true, view.path("productDesignerKey").path("stored").asBoolean());
        assertFalse(Js.stringify(view).contains("pd_test_key"));
        assertTrue(view.path("productDesignerUrl").asString().startsWith("http://127.0.0.1:"));
    }
}
