package bio.anode.ideagenerator;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ScientistTest {
    HttpServer server;
    final AtomicReference<String> auth = new AtomicReference<>(), body = new AtomicReference<>();
    volatile int status = 201;
    volatile String answer = "{\"id\":\"0193-job\",\"status\":\"QUEUED\"}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/ideations", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", status == 201 ? "application/json" : "application/problem+json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static Idea idea() {
        return new Idea(1, 1, "Singing bench", "A bench that hums. It reacts to who sits.", "expand", null, 0, 0, 5, 4, 5, "Novel mechanism.",
                null, null, 1, null, null, null, null, List.of("sound", "public space"), "", null, 0);
    }

    @Test
    void submitsAnIdeationJobWithTheIdeaAsTopic() {
        Scientist.Job job = Scientist.submit(url(), "ais_secret", idea(), "Sound in public spaces");
        assertEquals("Bearer ais_secret", auth.get());
        JsonNode sent = Js.parse(body.get());
        assertEquals(Scientist.MAX_GENERATIONS, sent.path("maxGenerations").asInt());
        assertEquals(Scientist.BUDGET_USD, sent.path("budgetUsd").asDouble());
        String topic = sent.path("topic").asString();
        assertTrue(topic.startsWith("# Title: Singing bench\n"), topic);
        assertTrue(topic.contains("## Keywords\nsound, public space"));
        assertTrue(topic.contains("## TL;DR\nA bench that hums.\n"), "first sentence of the pitch");
        assertTrue(topic.contains("Context: Sound in public spaces"));
        assertEquals(new Scientist.Job("0193-job", url() + "/v1/jobs/0193-job", "QUEUED"), job);
    }

    @Test
    void problemDetailsBecomeAReadableError() {
        status = 429;
        answer = "{\"type\":\"/problems/daily-submissions\",\"title\":\"Too many\",\"detail\":\"30 submissions per day\"}";
        AppError e = assertThrows(AppError.class, () -> Scientist.submit(url(), "k", idea(), ""));
        assertEquals(502, e.status);
        assertEquals("AI Scientist answered 429: 30 submissions per day", e.getMessage());
        status = 401;
        answer = "";
        assertEquals("scientist_auth", assertThrows(AppError.class, () -> Scientist.submit(url(), "bad", idea(), "")).code);
    }

    @Test
    void anUnreachableServerSaysWhere() {
        AppError e = assertThrows(AppError.class, () -> Scientist.submit("http://127.0.0.1:9", "k", idea(), ""));
        assertEquals("scientist_unreachable", e.code);
    }
}
