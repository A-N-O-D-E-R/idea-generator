package com.anode.ideagenerator.controller;

import com.anode.ideagenerator.AppError;
import com.anode.ideagenerator.config.Mode;
import com.anode.ideagenerator.service.ApiKeys;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Personal API keys for other apps ({@code Authorization: Bearer idg_...}). Managed from a browser session only: a
 * key can never create, list or revoke keys. Not available without login ({@code ideagen.auth=none}).
 */
@RestController
@RequestMapping("/api/api-keys")
@RequiredArgsConstructor
class ApiKeyController {
    private final ApiKeys keys;
    private final Mode mode;
    private final CurrentTenant tenant;

    /** The key itself is in this response only: it cannot be read again. */
    @PostMapping
    ApiKeys.Created create(@RequestBody(required = false) JsonNode request) {
        String t = sessionTenant();
        String name = request != null && request.path("name").isString() ? request.path("name").asString().strip() : "";
        if (name.isEmpty() || name.length() > 80) throw new AppError(400, "bad_name", "Give the key a name of 1 to 80 characters.");
        long days = request != null && request.path("days").canConvertToLong() ? request.path("days").asLong() : ApiKeys.DEFAULT_TTL.toDays();
        if (days < 1 || days > 365) throw new AppError(400, "bad_ttl", "A key lasts 1 to 365 days.");
        return keys.create(t, name, Duration.ofDays(days));
    }

    @GetMapping
    Map<String, List<ApiKeys.Key>> list() {
        return Map.of("keys", keys.list(sessionTenant()));
    }

    @DeleteMapping("/{id}")
    Map<String, Boolean> revoke(@PathVariable UUID id) {
        if (!keys.revoke(sessionTenant(), id)) throw new AppError(404, "not_found", "No such active key");
        return Map.of("revoked", true);
    }

    private String sessionTenant() {
        if (!mode.oauth()) throw new AppError(404, "not_available", "API keys need a login (ideagen.auth=oauth)");
        String id = tenant.id();
        if (SecurityContextHolder.getContext().getAuthentication() instanceof Authentication a
                && a.getAuthorities().stream().anyMatch(g -> ApiKeys.AUTHORITY.equals(g.getAuthority()))) {
            throw new AppError(403, "session_only", "API keys are managed from a signed-in browser session, not with a key.");
        }
        return id;
    }
}
