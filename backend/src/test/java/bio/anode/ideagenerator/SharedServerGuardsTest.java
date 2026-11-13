package bio.anode.ideagenerator;

import bio.anode.ideagenerator.config.Security;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Fixes from the multi-user security review: address checks, body cap, login allowlist. */
class SharedServerGuardsTest {
    static boolean priv(String ip) throws Exception {
        return Providers.isPrivate(InetAddress.getByName(ip));
    }

    @Test
    void ipv6FormsThatCarryAnIpv4AddressAreJudgedByIt() throws Exception {
        assertTrue(priv("64:ff9b::10.0.0.1"), "NAT64 of a private address");
        assertTrue(priv("64:ff9b::169.254.169.254"), "NAT64 of cloud metadata");
        assertFalse(priv("64:ff9b::8.8.8.8"), "NAT64 of a public address");
        assertTrue(priv("64:ff9b:1::1"), "local-use NAT64");
        assertTrue(priv("2002:0a00:0001::1"), "6to4 of 10.0.0.1");
        assertFalse(priv("2002:0808:0808::1"), "6to4 of 8.8.8.8");
        assertTrue(priv("2001:0:4136:e378:8000:63bf:3fff:fdd2"), "Teredo");
        assertFalse(priv("2606:4700:4700::1111"), "plain public IPv6");
        assertTrue(priv("::ffff:127.0.0.1"), "IPv4-mapped loopback");
    }

    @Test
    void theSharedClientChecksTheAddressOnEveryRequest() {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:9/v1/chat/completions")).GET();
        var e = assertThrows(Providers.ProviderError.class, () -> Providers.send(Providers.HTTP_NO_REDIRECT, req, Map.of()));
        assertEquals("private_address", e.code);
    }

    @Test
    void responseBodiesAreCapped() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(200, 0); // chunked, no length to trust
            byte[] kb = new byte[1024];
            try (var out = ex.getResponseBody()) {
                for (int i = 0; i < 64; i++) out.write(kb);
            } catch (IOException ignored) {
                // client hung up, as it should
            }
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            HttpClient client = HttpClient.newHttpClient();
            var big = assertThrows(IOException.class, () -> client.send(HttpRequest.newBuilder(uri).build(), Providers.capped(16 * 1024)));
            assertTrue(big.getMessage().contains("larger than"), big.getMessage());
            assertEquals(64 * 1024, client.send(HttpRequest.newBuilder(uri).build(), Providers.capped(1 << 20)).body().length());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theLoginAllowlistUsesStableIds() {
        Set<String> allowed = Security.allowed(" github:123,google:abc \n");
        assertEquals(Set.of("github:123", "google:abc"), allowed);
        assertDoesNotThrow(() -> Security.checkAllowed(allowed, "github", "123"));
        assertThrows(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class,
                () -> Security.checkAllowed(allowed, "github", "456"));
        assertDoesNotThrow(() -> Security.checkAllowed(Set.of(), "github", "456"), "empty = open sign-up");
    }
}
