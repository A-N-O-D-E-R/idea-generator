package bio.anode.ideagenerator;

import bio.anode.ideagenerator.config.Mode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretsTest {

    @Test
    void aCiphertextOnlyOpensAsTheTenantAndNameItWasWrittenFor(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("keys/keyset.json");
        Secrets.generate(file);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> Secrets.generate(file), "never overwrites a keyset");

        Secrets s = new Secrets(file.toString(), true);
        byte[] ct = s.encrypt("a", "openrouter", "sk-or-1");
        assertEquals("sk-or-1", s.decrypt("a", "openrouter", ct));
        assertEquals("sk-or-1", new Secrets(file.toString(), true).decrypt("a", "openrouter", ct), "the keyset file survives a restart");
        assertThrows(GeneralSecurityException.class, () -> s.decrypt("b", "openrouter", ct), "another tenant");
        assertThrows(GeneralSecurityException.class, () -> s.decrypt("a", "anthropic", ct), "another secret");
        assertThrows(GeneralSecurityException.class, () -> new Secrets("", false).decrypt("a", "openrouter", ct), "an ephemeral keyset");
    }

    @Test
    void oauthNeedsAKeysetNoneRunsOnLoopbackOnly() {
        assertTrue(assertThrows(IllegalStateException.class, () -> new Secrets("", true)).getMessage().contains("--generate-keyset"));
        assertEquals(false, Mode.of("none", "127.0.0.1").oauth());
        assertEquals(false, Mode.of("none", "localhost").oauth());
        assertEquals(false, Mode.of("none", "::1").oauth());
        for (String open : new String[]{"0.0.0.0", "", "192.168.1.2", "example.invalid"}) {
            assertThrows(IllegalStateException.class, () -> Mode.of("none", open), open);
        }
        assertEquals(true, Mode.of("oauth", "0.0.0.0").oauth());
        assertThrows(IllegalStateException.class, () -> Mode.of("basic", "127.0.0.1"));
    }
}
