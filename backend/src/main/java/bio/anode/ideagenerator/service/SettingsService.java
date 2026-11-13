package bio.anode.ideagenerator.service;

import bio.anode.ideagenerator.Js;
import bio.anode.ideagenerator.Providers;
import bio.anode.ideagenerator.Providers.AppSettings;
import bio.anode.ideagenerator.Providers.Provider;
import bio.anode.ideagenerator.Secrets;
import bio.anode.ideagenerator.repository.SettingsRepository;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * App settings per tenant: plain settings as JSON, secrets encrypted (see {@link Secrets}). Secrets are the provider
 * API keys, named by provider id, and the AI Scientist key, named "scientist". The browser only ever sees
 * {stored: true|false} for a secret.
 */
@Slf4j
public class SettingsService {
    static final String SCIENTIST = "scientist";
    static final List<String> PLAIN = List.of("baseUrls", "models", "projectsRoot", "maxUsdPerDay", "workspaceRoot", "scientistUrl", "reposRoots");

    private final SettingsRepository repo;
    private final Secrets secrets;
    /** Shared server (ideagen.auth=oauth): projects live under dataDir/tenants/<id>/projects, workspace and repo scan off. */
    private final Path dataDir;

    public SettingsService(SettingsRepository repo, Secrets secrets, Path dataDir) {
        this.repo = repo;
        this.secrets = secrets;
        this.dataDir = dataDir == null ? null : dataDir.toAbsolutePath().normalize();
    }

    public boolean shared() {
        return dataDir != null;
    }

    /** The tenant's own projects folder on a shared server. Tenant ids are UUIDs (or "local"), never path syntax. */
    Path projectsOf(String t) {
        if (!t.matches("[A-Za-z0-9-]+")) throw new IllegalArgumentException("bad tenant id");
        return dataDir.resolve("tenants").resolve(t).resolve("projects");
    }

    /** Providers that take an API key. */
    static List<Provider> keyed() {
        return Arrays.stream(Provider.values()).filter(p -> p.env != null).toList();
    }

    private ObjectNode stored(String t) {
        return Js.parse(repo.plain(t)) instanceof ObjectNode o ? o : Js.object();
    }

    /** name -> plaintext. A secret that no longer decrypts (keyset changed) counts as not stored. */
    private Map<String, String> secrets(String t) {
        Map<String, String> out = new HashMap<>();
        repo.eachSecret(t, (name, ciphertext) -> {
            try {
                out.put(name, secrets.decrypt(t, name, ciphertext));
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
        AppSettings s = Providers.mergeAppSettings(raw);
        return shared() ? s.sharedWith(projectsOf(t).toString()) : s;
    }

    /** The settings without any secret. */
    public static ObjectNode plain(AppSettings s) {
        ObjectNode o = Js.object();
        o.set("baseUrls", Js.tree(s.baseUrls()));
        o.set("models", Js.tree(s.models()));
        o.put("maxUsdPerDay", s.maxUsdPerDay());
        o.put("scientistUrl", s.scientistUrl());
        if (!s.shared()) { // server paths are nobody's business on a shared server
            o.put("projectsRoot", s.projectsRoot());
            o.put("workspaceRoot", s.workspaceRoot());
            o.put("reposRoots", s.reposRoots());
        }
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
        AppSettings merged = Providers.mergeAppSettings(cur);
        // On a shared server the folder settings are ignored, so they are not stored either.
        String json = Js.stringify(plain(shared() ? merged.sharedWith("") : merged));
        Map<String, byte[]> changes = new LinkedHashMap<>();
        for (Provider p : keyed()) secretChange(changes, t, p.id(), body.path("keys").path(p.id()));
        secretChange(changes, t, SCIENTIST, body.path("scientistKey"));
        repo.save(t, json, changes);
    }

    /** name -> ciphertext of the new value, or null to delete; nothing when the body leaves the secret alone. */
    private void secretChange(Map<String, byte[]> changes, String t, String name, JsonNode v) {
        if (!v.isString()) return;
        String value = Js.cut(Js.trim(v.stringValue()), 500);
        try {
            changes.put(name, value.isEmpty() ? null : secrets.encrypt(t, name, value));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not encrypt a secret", e); // no value in the message
        }
    }
}
