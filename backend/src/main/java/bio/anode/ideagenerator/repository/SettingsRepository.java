package bio.anode.ideagenerator.repository;

import java.util.Map;
import java.util.function.BiConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Per-tenant settings: plain ones as JSON text (tenant_settings), secrets as ciphertext (tenant_secrets). */
@Repository
@RequiredArgsConstructor
public class SettingsRepository {
    private final JdbcClient db;
    private final TransactionTemplate tx;

    /** The plain settings JSON, "{}" when nothing is stored. */
    public String plain(String t) {
        return db.sql("SELECT settings FROM tenant_settings WHERE tenant_id = ?").param(t).query(String.class).optional().orElse("{}");
    }

    /** Hands every stored secret (name, ciphertext) to the callback. */
    public void eachSecret(String t, BiConsumer<String, byte[]> fn) {
        db.sql("SELECT name, ciphertext FROM tenant_secrets WHERE tenant_id = ?").param(t)
                .query(rs -> {
                    fn.accept(rs.getString("name"), rs.getBytes("ciphertext"));
                });
    }

    /** Store the plain settings and apply the secret changes (name -> ciphertext, null = delete) in one transaction. */
    public void save(String t, String plainJson, Map<String, byte[]> secretChanges) {
        tx.executeWithoutResult(s -> {
            db.sql("""
                            INSERT INTO tenant_settings (tenant_id, settings) VALUES (?, ?)
                             ON CONFLICT (tenant_id) DO UPDATE SET settings = excluded.settings, updated_at = now()""")
                    .params(t, plainJson).update();
            secretChanges.forEach((name, ct) -> {
                if (ct == null) {
                    db.sql("DELETE FROM tenant_secrets WHERE tenant_id = ? AND name = ?").params(t, name).update();
                } else {
                    db.sql("""
                                    INSERT INTO tenant_secrets (tenant_id, name, ciphertext) VALUES (?, ?, ?)
                                     ON CONFLICT (tenant_id, name) DO UPDATE SET ciphertext = excluded.ciphertext, updated_at = now()""")
                            .params(t, name, ct).update();
                }
            });
        });
    }
}
