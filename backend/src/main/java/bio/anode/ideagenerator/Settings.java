package bio.anode.ideagenerator;

import bio.anode.ideagenerator.Providers.AppSettings;
import bio.anode.ideagenerator.Providers.Provider;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * App settings per tenant, in the database: plain settings as JSON (tenant_settings), secrets encrypted
 * (tenant_secrets, see {@link Secrets}). Secrets are the provider API keys, named by provider id, and the
 * AI Scientist key, named "scientist". The browser only ever sees {stored: true|false} for a secret.
 */
public class Settings {
    private static final Logger log = LoggerFactory.getLogger(Settings.class);
    static final String SCIENTIST = "scientist";
    static final List<String> PLAIN = List.of("baseUrls", "models", "projectsRoot", "maxUsdPerDay", "workspaceRoot", "scientistUrl", "reposRoots");

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Secrets secrets;

    public Settings(DataSource ds, Secrets secrets) {
        this.db = JdbcClient.create(ds);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        this.secrets = secrets;
    }

    /** Providers that take an API key. */
    static List<Provider> keyed() {
        return java.util.Arrays.stream(Provider.values()).filter(p -> p.env != null).toList();
    }

    private ObjectNode stored(String t) {
        String json = db.sql("SELECT settings FROM tenant_settings WHERE tenant_id = ?").param(t).query(String.class).optional().orElse("{}");
        JsonNode n = Js.parse(json);
        return n instanceof ObjectNode o ? o : Js.object();
    }

    /** name -> plaintext. A secret that no longer decrypts (keyset changed) counts as not stored. */
    private Map<String, String> secrets(String t) {
        Map<String, String> out = new HashMap<>();
        db.sql("SELECT name, ciphertext FROM tenant_secrets WHERE tenant_id = ?").param(t).query(rs -> {
            String name = rs.getString("name");
            try {
                out.put(name, secrets.decrypt(t, name, rs.getBytes("ciphertext")));
            } catch (GeneralSecurityException e) {
                log.warn("Stored secret '{}' of tenant {} does not decrypt with this keyset; ignoring it", name, t);
            }
        });
        return out;
    }

    /** Everything the engine needs for this tenant, keys decrypted. Server side only: never send this to the browser. */
    public AppSettings get(String t) {
        ObjectNode raw = stored(t);
        Map<String, String> plain = secrets(t);
        ObjectNode keys = raw.putObject("keys");
        for (Provider p : keyed()) if (plain.containsKey(p.id())) keys.put(p.id(), plain.get(p.id()));
        raw.put("scientistKey", plain.getOrDefault(SCIENTIST, ""));
        return Providers.mergeAppSettings(raw);
    }

    /** The settings without any secret. */
    static ObjectNode plain(AppSettings s) {
        ObjectNode o = Js.object();
        o.set("baseUrls", Js.tree(s.baseUrls()));
        o.set("models", Js.tree(s.models()));
        o.put("projectsRoot", s.projectsRoot());
        o.put("maxUsdPerDay", s.maxUsdPerDay());
        o.put("workspaceRoot", s.workspaceRoot());
        o.put("scientistUrl", s.scientistUrl());
        o.put("reposRoots", s.reposRoots());
        return o;
    }

    /** What the browser sees: the plain settings, and {stored} for each secret. */
    public ObjectNode view(String t) {
        AppSettings s = get(t);
        ObjectNode o = Js.object();
        ObjectNode keys = o.putObject("keys");
        for (Provider p : keyed()) keys.putObject(p.id()).put("stored", s.keys().containsKey(p.id()));
        o.setAll(plain(s));
        o.putObject("scientistKey").put("stored", !s.scientistKey().isEmpty());
        return o;
    }

    /**
     * PUT /api/settings. Plain settings present in the body replace the stored ones (then validated);
     * a secret given as a string is set, "" deletes it, anything else (absent, {stored}) keeps it.
     */
    public synchronized void save(String t, JsonNode body) {
        ObjectNode cur = stored(t);
        for (String f : PLAIN) if (body.has(f)) cur.set(f, body.get(f));
        String json = Js.stringify(plain(Providers.mergeAppSettings(cur)));
        tx.executeWithoutResult(s -> {
            db.sql("""
                            INSERT INTO tenant_settings (tenant_id, settings) VALUES (?, ?)
                             ON CONFLICT (tenant_id) DO UPDATE SET settings = excluded.settings, updated_at = now()""")
                    .params(t, json).update();
            for (Provider p : keyed()) setSecret(t, p.id(), body.path("keys").path(p.id()));
            setSecret(t, SCIENTIST, body.path("scientistKey"));
        });
    }

    private void setSecret(String t, String name, JsonNode v) {
        if (!v.isString()) return;
        String value = Js.cut(Js.trim(v.stringValue()), 500);
        if (value.isEmpty()) {
            db.sql("DELETE FROM tenant_secrets WHERE tenant_id = ? AND name = ?").params(t, name).update();
            return;
        }
        byte[] ct;
        try {
            ct = secrets.encrypt(t, name, value);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not encrypt a secret", e); // no value in the message
        }
        db.sql("""
                        INSERT INTO tenant_secrets (tenant_id, name, ciphertext) VALUES (?, ?, ?)
                         ON CONFLICT (tenant_id, name) DO UPDATE SET ciphertext = excluded.ciphertext, updated_at = now()""")
                .params(t, name, ct).update();
    }
}
