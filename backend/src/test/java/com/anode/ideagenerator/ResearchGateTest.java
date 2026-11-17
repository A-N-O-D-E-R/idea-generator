package com.anode.ideagenerator;

import static com.anode.ideagenerator.repository.TenantRepository.LOCAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anode.ideagenerator.Providers.ChatRequest;
import com.anode.ideagenerator.Providers.ChatResult;
import com.anode.ideagenerator.model.Board;
import com.anode.ideagenerator.model.Idea;
import com.anode.ideagenerator.model.Research;
import com.anode.ideagenerator.repository.BoardRepository;
import com.anode.ideagenerator.repository.IdeaRepository;
import com.anode.ideagenerator.service.Ai;
import com.anode.ideagenerator.service.JudgeService;
import com.anode.ideagenerator.service.ResearchService;
import com.anode.ideagenerator.service.SettingsService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

/** The research gate: an ideation job at a fake ai-scientist, polled until done, read by the judge, written into idea.json. */
@SpringBootTest(properties = {"idea.autopilot=false", "idea.research.poll-ms=3600000"})
class ResearchGateTest {
    static HttpServer scientist;
    static volatile String jobStatus = "RUNNING";
    static volatile String jobBody = "";
    static final List<ChatRequest> judgeCalls = new CopyOnWriteArrayList<>();
    static volatile int originality = 2;

    static final String IDEAS = """
            [{"Name":"a","Title":"Barcode ledger","Short Hypothesis":"Scanning cuts stock-outs by half.",
              "Related Work":"LIMS cover inventory but are heavy.","Abstract":"x","Experiments":["e"],
              "Risk Factors and Limitations":"Depends on scanning discipline."},
             {"Name":"b","Title":"Shelf cameras","Short Hypothesis":"Cameras replace scanning.",
              "Related Work":"Retail shelf vision is mature.","Abstract":"x","Experiments":["e"],
              "Risk Factors and Limitations":"Privacy in shared labs."}]""";

    @BeforeAll
    static void startScientist() throws Exception {
        scientist = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        scientist.createContext("/v1/ideations", ex -> reply(ex, 201, "{\"id\":\"job-1\",\"status\":\"QUEUED\"}"));
        scientist.createContext("/v1/jobs/job-1", ex -> reply(ex, 200, jobBody.isEmpty()
                ? "{\"id\":\"job-1\",\"status\":\"" + jobStatus + "\",\"result\":null}" : jobBody));
        scientist.start();
    }

    static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    @AfterAll
    static void stopScientist() {
        scientist.stop(0);
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
                if (r.schemaName().equals("judgement")) {
                    judgeCalls.add(r);
                    return new ChatResult(Js.parse("{\"reason\":\"r\",\"originality\":" + originality + ",\"feasibility\":3,\"impact\":3,\"keywords\":[\"lab\"]}"), 0.001);
                }
                return new ChatResult(Js.parse("""
                        {"name":"Ledger","slug":"ledger","oneLiner":"o","problem":"p","audience":"a","mvp":["m"],"outOfScope":["o"],"stack":"s",
                         "firstSteps":["f"],"openQuestions":["q"],"risks":["r"]}"""), 0.001);
            };
            return new Ai.Io(s -> chat, url -> { throw new AssertionError("no fetch"); }, g -> List.of(), p -> { }, false, q -> List.of());
        }
    }

    @Autowired SettingsService settings;
    @Autowired BoardRepository boards;
    @Autowired IdeaRepository ideas;
    @Autowired ResearchService research;
    @Autowired JudgeService judge;
    @Autowired com.anode.ideagenerator.service.ProjectService projects;
    @Autowired com.anode.ideagenerator.repository.TenantRepository tenants;

    Idea idea;

    @BeforeEach
    void setUp() {
        settings.save(LOCAL, Js.parse("{\"scientistUrl\":\"http://127.0.0.1:" + scientist.getAddress().getPort() + "\",\"scientistKey\":\"ais_test\"}"));
        Board board = boards.insert(LOCAL, "Labs", "Help small labs track reagent stock", Board.defaultConfig(), null);
        idea = ideas.insert(LOCAL, new com.anode.ideagenerator.model.NewIdea(board.id(), "Barcode ledger", "Scan a bottle in and out.", "seed", null, 0, 0, 0));
        jobStatus = "RUNNING";
        jobBody = "";
        originality = 2;
        judgeCalls.clear();
    }

    @Test
    void aRunningJobStaysPendingAndADoneJobIsRecordedThenJudgedWithItsFindings() throws Exception {
        assertEquals("job-1", research.submit(LOCAL, idea.id()).id());
        assertEquals("pending", research.get(LOCAL, idea.id()).orElseThrow().status());

        assertEquals("pending", research.refresh(LOCAL, idea.id()).orElseThrow().status(), "still running");
        assertTrue(judgeCalls.isEmpty());

        jobStatus = "DONE";
        jobBody = "{\"id\":\"job-1\",\"status\":\"DONE\",\"result\":" + IDEAS + "}";
        Research r = research.refresh(LOCAL, idea.id()).orElseThrow();
        assertEquals("done", r.status());
        assertEquals(List.of("Scanning cuts stock-outs by half.", "Cameras replace scanning."), r.hypotheses());
        assertTrue(r.relatedWork().contains("Barcode ledger: LIMS cover inventory but are heavy."), r.relatedWork());
        assertEquals(List.of("Depends on scanning discipline.", "Privacy in shared labs."), r.riskFactors());

        judge.idle();
        assertEquals(1, judgeCalls.size(), "a finished gate re-queues the idea for judging");
        String shown = judgeCalls.getFirst().user();
        assertTrue(shown.contains("Research already done on this idea") && shown.contains("Scanning cuts stock-outs by half.")
                && shown.contains("Depends on scanning discipline."), shown);
        Research judged = research.get(LOCAL, idea.id()).orElseThrow();
        assertEquals("known", judged.noveltyVerdict(), "originality 2 after reading the related work");
        assertEquals(2, ideas.require(LOCAL, idea.id()).originality());

        // a second refresh of a finished gate changes nothing and judges nothing
        research.refresh(LOCAL, idea.id());
        judge.idle();
        assertEquals(1, judgeCalls.size());
    }

    @Test
    void anEndedJobWithoutIdeasOrAFailedOneFailsTheGate() {
        research.submit(LOCAL, idea.id());
        jobStatus = "FAILED";
        jobBody = "{\"id\":\"job-1\",\"status\":\"FAILED\",\"error\":\"out of budget\",\"result\":null}";
        Research r = research.refresh(LOCAL, idea.id()).orElseThrow();
        assertEquals("failed", r.status());
        assertTrue(r.error().contains("out of budget"), r.error());
        assertTrue(judgeCalls.isEmpty());
    }

    @Test
    void theProjectsIdeaJsonCarriesTheResearchBlockOnlyOnceTheGateIsDone() throws Exception {
        Path root = java.nio.file.Files.createTempDirectory("ideagen-projects-");
        settings.save(LOCAL, Js.parse("{\"projectsRoot\":" + Js.stringify(root.toString()) + "}"));

        Idea plain = ideas.insert(LOCAL, new com.anode.ideagenerator.model.NewIdea(idea.boardId(), "No research", "p", "seed", null, 0, 0, 0));
        JsonNode without = Js.parse(java.nio.file.Files.readString(Path.of((String) projects.create(LOCAL, plain.id()).get("path"), "idea.json")));
        assertTrue(without.path("research").isMissingNode(), "no gate, no research block");

        research.submit(LOCAL, idea.id());
        jobBody = "{\"id\":\"job-1\",\"status\":\"DONE\",\"result\":" + IDEAS + "}";
        research.refresh(LOCAL, idea.id());
        judge.idle();
        originality = 4;
        judge.judge(LOCAL, idea.id());   // read the related work again: novel
        JsonNode withIt = Js.parse(java.nio.file.Files.readString(Path.of((String) projects.create(LOCAL, idea.id()).get("path"), "idea.json")));
        JsonNode block = withIt.path("research");
        assertEquals("novel", block.path("noveltyVerdict").asString());
        assertEquals(2, block.path("hypotheses").size());
        assertEquals(2, block.path("riskFactors").size());
        assertTrue(block.path("jobUrl").asString().endsWith("/v1/jobs/job-1"));
        assertFalse(block.path("relatedWork").asString().isEmpty());
    }

}
