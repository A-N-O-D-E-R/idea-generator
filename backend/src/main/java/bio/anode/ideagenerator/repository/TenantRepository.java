package bio.anode.ideagenerator.repository;

import bio.anode.ideagenerator.model.Me;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Tenants and the login identities that own them. */
@Repository
@RequiredArgsConstructor
public class TenantRepository {
    /** The one tenant when ideagen.auth=none. */
    public static final String LOCAL = "local";

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final TemplateRepository templates;

    /** Create the tenant if new, with the default templates. */
    public synchronized void ensure(String id, String name, String email) {
        tx.executeWithoutResult(s -> {
            int created = db.sql("INSERT INTO tenants (id, name, email) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
                    .params(id, name == null ? "" : name, email).update();
            if (created > 0 || templates.count(id) == 0) templates.addDefaults(id);
        });
    }

    /**
     * The tenant of this login, created on first login. One identity = one tenant: there is no merging by email
     * (an account-takeover path) and no linking of a second provider.
     */
    public synchronized String login(String provider, String subject, String name, String email) {
        return tx.execute(s -> {
            String owner = db.sql("SELECT tenant_id FROM identities WHERE provider = ? AND subject = ?").params(provider, subject)
                    .query(String.class).optional().orElse(null);
            if (owner == null) {
                owner = UUID.randomUUID().toString();
                ensure(owner, name, email);
                db.sql("INSERT INTO identities (provider, subject, tenant_id, email) VALUES (?, ?, ?, ?)").params(provider, subject, owner, email).update();
            } else {
                db.sql("UPDATE tenants SET name = ?, email = ? WHERE id = ?").params(name == null ? "" : name, email, owner).update();
                db.sql("UPDATE identities SET email = ? WHERE provider = ? AND subject = ?").params(email, provider, subject).update();
            }
            return owner;
        });
    }

    public synchronized Me me(String t) {
        return db.sql("SELECT name, email FROM tenants WHERE id = ?").param(t)
                .query((r, n) -> new Me(r.getString("name"), r.getString("email"))).optional().orElse(new Me("", null));
    }
}
