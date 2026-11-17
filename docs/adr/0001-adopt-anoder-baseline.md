# 1. Adopt the A-N-O-D-E-R baseline

Date: 2026-11-17 — Status: accepted

## Context

The four A-N-O-D-E-R apps (idea-generator, ai-scientist, productDesigner, digital-factory) each did auth, Jira, LLM
access, build and frontend tooling their own way. idea-generator had a hand-written security chain, the groupId
`bio.anode` and no way for another app to call it without a browser session.

## Decision

Align on one baseline built on `springboot-starter-collection`:

- `io.github.a-n-o-d-e-r` coordinates, base package `com.anode.ideagenerator` (was `bio.anode.ideagenerator`);
- `spring-boot-starter-parent` 4.1.2, Java 25; the collection BOM (0.2.0) is imported and brings the Spring AI BOM;
- with `ideagen.auth=oauth` the security starter builds the chain (`anode.security.env-oidc`, derived from
  `ideagen.auth` in `application.yaml`). GitHub and Google are two Spring client registrations, so the login is
  multi-provider without starter changes. App specifics are beans the starter picks up (`config/Security.java`):
  the tenant behind each login and the sign-in allow-list, only-configured providers, JSON 401/403, `POST /api/logout`,
  `/api/**` authenticated while the static frontend stays public;
- `ideagen.auth=none` (the default: one local tenant, no login) keeps its own small chain, with the loopback-host
  guard against DNS rebinding: the starter has no "no login" mode, by design;
- other apps call this one with a personal API key (`Authorization: Bearer idg_...`, `V3__api_keys.sql`,
  `ApiKeys`, `/api/api-keys`): no session, no CSRF token, managed from a browser session only. Same shape as
  ai-scientist's `ais_` keys;
- CI is the collection's reusable workflow, updates come from its Renovate preset.

## Consequences

- The Okta jar the security starter brings is excluded in the POM: its environment post-processor breaks Spring AI's
  configuration binding (found in ai-scientist). Revisit when the starter marks Okta optional.
- The starter also enables `oauth2Client()`, which has its own redirect filter: the "only configured providers"
  resolver is set on both.
- Not done here: the frontend still uses caret ranges and has no unit tests (`pnpm test` is skipped when absent);
  `Providers.java` is not yet Spring AI (Phase 3).
- A collection release (0.2.0) is a prerequisite for a green CI.
