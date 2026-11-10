# Multi-user design

Goal: the app can run on a server for several people, reusing the approach of `perso/ai-scientist`
(OAuth login, tenant per person, keys encrypted server-side with Tink, Postgres + Flyway), without
losing the local single-user tool (workspace filing into `~/Documents/Code/Idea`, project folders, reveal).

## Modes: `ideagen.auth`

| | `none` (default) | `oauth` |
|---|---|---|
| Who | one implicit tenant `local` | one tenant per person (GitHub / Google identities) |
| Bind | must be loopback; startup fails otherwise | any |
| Login | none | Spring Security `oauth2Login`, JDBC sessions, CSRF via `XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header |
| Env key fallback (`OPENROUTER_API_KEY`...) | yes | **no**: every LLM call uses the tenant's own stored key |
| Filesystem | as today: `projectsRoot`, `workspaceRoot`, `reposRoots`, reveal | projects under `ideagen.data-dir/tenants/<id>/projects/`, downloadable as zip; workspace filing, repo scan and reveal are off (settings ignored, UI hides them) |

## Data

- Postgres 17, Flyway migrations (`V1__schema.sql` = upstream schema + tenancy). SQLite is dropped.
- Dev: `spring-boot-docker-compose` with `compose.yaml` (postgres:17-alpine). Tests: Testcontainers (postgres:17-alpine).
- `tenants`, `identities(provider, subject)`; `boards.tenant_id`, `templates.tenant_id` (defaults seeded per new tenant),
  report synthesis per tenant. Ideas, edges, leads, keywords, spend hang off boards.
- **Every repository query is scoped by tenant.** Another tenant's board/idea/lead/template answers 404, never 403
  (no existence leak). Dashboard, report, keywords, announce targets, spinoffs: own boards only.

## Settings and keys

- Per tenant in the DB: non-secret settings as JSON (`tenant_settings`), secrets in `tenant_secrets(tenant_id, name, ciphertext)`.
  Secrets = provider API keys and the AI Scientist key.
- Tink AEAD (AES256-GCM), associated data = `tenant_id + ":" + name`, keyset from `ideagen.keyset-file`
  (`--generate-keyset=<file>` CLI). No keyset in `none` mode: ephemeral keyset with a warning (stored keys are lost on restart);
  in `oauth` mode a missing keyset is a startup error.
- API: `GET /api/settings` (no secret values: per secret `{stored: true|false}`), `PUT /api/settings`
  (secret value = set, `""` = delete, absent = keep). `PUT /api/runtime-settings` and `settingsReceived` are removed.
- Browser: settings come from the server. On first load, keys still in `localStorage` (`ideagen.settings`) are pushed once
  and removed from `localStorage`.
- Keys never in logs, responses, events or exports.

## Engine

- `Supplier<AppSettings>` becomes a per-tenant lookup; every operation and the autopilot use the settings of the
  board's tenant. Global USD/day limit is per tenant (spend of the tenant's boards).

## Out of scope

Admin UI, quotas beyond the existing USD limits, account deletion, abuse screening, multiple instances sharing the
autopilot (single instance assumed: the autopilot runs in-process).
