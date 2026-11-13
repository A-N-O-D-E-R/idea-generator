package bio.anode.ideagenerator.controller;

import static bio.anode.ideagenerator.controller.Requests.body;

import bio.anode.ideagenerator.AppError;
import bio.anode.ideagenerator.Js;
import bio.anode.ideagenerator.config.Mode;
import bio.anode.ideagenerator.model.Me;
import bio.anode.ideagenerator.service.ProviderService;
import bio.anode.ideagenerator.service.SettingsService;
import bio.anode.ideagenerator.service.TenantService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Who is signed in, the app settings (secrets go in, never out: {stored: true|false} instead) and the providers. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
class SettingsController {
    private final SettingsService settings;
    private final ProviderService providers;
    private final TenantService tenants;
    private final Mode mode;
    private final CurrentTenant tenant;

    /** Who is signed in, and the mode, so the UI can hide what a shared server does not do. */
    @GetMapping("/me")
    Object me() {
        if (!mode.oauth()) return Js.obj("mode", "none");
        Me me = tenants.me(tenant.id());
        return Js.obj("mode", "oauth", "name", me.name(), "email", me.email());
    }

    private Map<String, Object> settingsView() {
        String t = tenant.id();
        Map<String, Object> out = providers.status(t);
        out.put("settings", settings.view(t));
        return out;
    }

    @GetMapping("/settings")
    Object getSettings() {
        return settingsView();
    }

    @PutMapping("/settings")
    Object putSettings(@RequestBody(required = false) byte[] raw) {
        JsonNode b = body(raw);
        if (!b.isObject()) throw new AppError(400, "bad_request", "Settings must be an object");
        settings.save(tenant.id(), b);
        return settingsView();
    }

    @GetMapping("/providers")
    Object providers() {
        return providers.status(tenant.id());
    }

    @PostMapping("/providers/{id:[a-z]+}/models")
    Object models(@PathVariable String id, @RequestBody(required = false) byte[] raw) {
        body(raw);
        return providers.models(tenant.id(), id);
    }
}
