package bio.anode.ideagenerator;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encryption of tenant secrets (provider API keys, the AI Scientist key) with Tink AEAD (AES256-GCM).
 * The associated data is "tenant:name", so a ciphertext only decrypts as the secret it was written as.
 * The keyset comes from ideagen.keyset-file; without one (auth=none only) an ephemeral keyset is used and
 * stored keys do not survive a restart. Generate a keyset with --generate-keyset=&lt;file&gt;.
 */
public class Secrets {
    private static final Logger log = LoggerFactory.getLogger(Secrets.class);

    private final Aead aead;

    public Secrets(String keysetFile, boolean required) throws GeneralSecurityException, IOException {
        AeadConfig.register();
        KeysetHandle handle;
        if (keysetFile != null && !keysetFile.isBlank()) {
            handle = TinkJsonProtoKeysetFormat.parseKeyset(Files.readString(Path.of(keysetFile)), InsecureSecretKeyAccess.get());
        } else if (required) {
            throw new IllegalStateException("ideagen.keyset-file is required with ideagen.auth=oauth; create one with --generate-keyset=<file>");
        } else {
            log.warn("No ideagen.keyset-file: using an ephemeral keyset. API keys saved in the settings are lost on restart. "
                    + "Create a keyset with --generate-keyset=<file> and set IDEAGEN_KEYSET_FILE.");
            handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM);
        }
        aead = handle.getPrimitive(RegistryConfiguration.get(), Aead.class);
    }

    private static byte[] ad(String tenant, String name) {
        return (tenant + ":" + name).getBytes(StandardCharsets.UTF_8);
    }

    public byte[] encrypt(String tenant, String name, String plaintext) throws GeneralSecurityException {
        return aead.encrypt(plaintext.getBytes(StandardCharsets.UTF_8), ad(tenant, name));
    }

    public String decrypt(String tenant, String name, byte[] ciphertext) throws GeneralSecurityException {
        return new String(aead.decrypt(ciphertext, ad(tenant, name)), StandardCharsets.UTF_8);
    }

    /** Write a new keyset (owner read/write only); never overwrites. */
    public static void generate(Path file) throws GeneralSecurityException, IOException {
        AeadConfig.register();
        String json = TinkJsonProtoKeysetFormat.serializeKeyset(KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM),
                InsecureSecretKeyAccess.get());
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException notPosix) {
            Files.createFile(file);
        }
        Files.writeString(file, json);
    }
}
