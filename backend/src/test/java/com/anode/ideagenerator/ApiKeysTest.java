package com.anode.ideagenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.anode.ideagenerator.config.Security;
import com.anode.ideagenerator.repository.TenantRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/** ideagen.auth=oauth: other apps call the API with a personal key, no session and no CSRF token. */
@SpringBootTest(properties = {"ideagen.auth=oauth", "idea.autopilot=false",
        "spring.security.oauth2.client.registration.github.client-id=test-client",
        "spring.security.oauth2.client.registration.github.client-secret=test-secret"})
class ApiKeysTest {
    static final Path dataDir;
    static final Path keyset;

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

    @Autowired WebApplicationContext context;
    @Autowired TenantRepository tenants;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    static RequestPostProcessor as(String tenant) {
        return oauth2Login().oauth2User(new Security.TenantUser(tenant, "test", new DefaultOAuth2User(List.of(), Map.of("id", 1), "id")));
    }

    String tenant(String name) {
        return tenants.login("github", "id-" + name + "-" + System.nanoTime(), name, null);
    }

    JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, int status) throws Exception {
        var res = mvc.perform(req).andReturn().getResponse();
        assertEquals(status, res.getStatus(), req + " -> " + res.getContentAsString());
        return res.getContentAsString().isEmpty() ? Js.object() : Js.parse(res.getContentAsString());
    }

    JsonNode createKey(String tenant, String name) throws Exception {
        return json(post("/api/api-keys").contentType(MediaType.APPLICATION_JSON).content(Js.stringify(Map.of("name", name)))
                .with(as(tenant)).with(csrf()), 200);
    }

    @Test
    void aKeyAuthenticatesAsItsTenantWithoutSessionOrCsrfToken() throws Exception {
        String a = tenant("alice"), b = tenant("bob");
        String key = createKey(a, "ci").path("key").asString();
        assertTrue(key.startsWith("idg_"), key);

        assertEquals("alice", json(get("/api/me").header("Authorization", "Bearer " + key), 200).path("name").asString());
        // a state-changing call: no CSRF token, no session
        JsonNode board = json(post("/api/boards").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"from a key\"}")
                .header("Authorization", "Bearer " + key), 200);
        long id = board.path("board").path("id").asLong();
        assertTrue(id > 0);
        // it is alice's board, bob does not see it
        assertFalse(json(get("/api/boards").with(as(b)), 200).toString().contains("from a key"));
        assertTrue(json(get("/api/boards").with(as(a)), 200).toString().contains("from a key"));
    }

    @Test
    void aKeyCannotManageKeysAndTheListNeverShowsOne() throws Exception {
        String a = tenant("carol");
        JsonNode created = createKey(a, "first");
        String key = created.path("key").asString();

        assertEquals("session_only", json(post("/api/api-keys").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")
                .header("Authorization", "Bearer " + key), 403).path("code").asString());
        json(get("/api/api-keys").header("Authorization", "Bearer " + key), 403);

        String listed = json(get("/api/api-keys").with(as(a)), 200).toString();
        assertTrue(listed.contains("first"));
        assertFalse(listed.contains(key), "the key is shown once, at creation");
    }

    @Test
    void aRevokedOrMalformedKeyIs401() throws Exception {
        String a = tenant("dave");
        JsonNode created = createKey(a, "short-lived");
        String key = created.path("key").asString();
        json(get("/api/me").header("Authorization", "Bearer " + key), 200);

        json(delete("/api/api-keys/" + created.path("id").asString()).with(as(a)).with(csrf()), 200);
        assertEquals(401, mvc.perform(get("/api/me").header("Authorization", "Bearer " + key)).andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(get("/api/me").header("Authorization", "Bearer idg_not-a-key")).andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(get("/api/me").header("Authorization", "Bearer " + key.substring(0, key.length() - 1) + "0")).andReturn().getResponse().getStatus());
    }

    @Test
    void keysAreCappedPerTenant() throws Exception {
        String a = tenant("erin");
        for (int i = 0; i < 10; i++) createKey(a, "k" + i);
        assertEquals("too_many_keys", json(post("/api/api-keys").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"11th\"}")
                .with(as(a)).with(csrf()), 409).path("code").asString());
    }
}
