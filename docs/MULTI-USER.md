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

## Implementation notes (where the code differs from the above, and why)

- **Tenant ids** are text: `local` in `none` mode, a random UUID per login otherwise (also the folder name under `tenants/`).
- **One identity = one tenant.** Signing in with GitHub and then Google gives two accounts. Merging by email is an
  account-takeover path and linking needs an account UI, which is out of scope. GitHub asks only `read:user`; emails are
  for display.
- **CSRF in both modes, and a loopback `Host` check in `none` mode.** The local server holds keys and writes files; a
  cross-site form or a DNS-rebound name could otherwise drive it from any web page.
- **No private addresses on a shared server.** Base URLs, the AI Scientist address and seed links are user-given URLs the
  server would fetch (SSRF). In `oauth` mode they must be http(s) to public addresses, redirects are not followed, and the
  local providers (Ollama, MLX, LM Studio) are hidden and refused. Not covered: DNS rebinding between the check and the connect.
- **API shapes.** `GET`/`PUT /api/settings` answer the provider status (as `GET /api/providers`) plus `settings`, where each
  secret is `{stored}`. `keySource` is `stored | env | null` (was `browser`). The legacy browser key is `ideagen.settings.v1`.
  `GET /api/me` exists in both modes (`{mode: "none"}` locally).
- **404 vs. delete.** Reads and changes of another tenant's objects answer 404; deletes answer `{"ok": false}`, exactly as
  for an id that does not exist (no existence leak, upstream contract kept). `PUT`/`DELETE /api/templates/{id}` for a
  missing or foreign template now answer 404 in both modes (before: `PUT` returned the first template).
- **Reveal** on a shared server answers 403 `not_available`; the UI shows *Download (.zip)* instead. The zip route works in
  both modes. Idea JSON and the report still carry `projectPath` (a server path) in `oauth` mode; the UI does not show it.
- **Storage.** JSON columns are `text` (jsonb would reorder keys) and timestamps stay UTC text, so every JSON shape is as
  before. Spend has a `tenant_id` (board 0 = the tenant's report synthesis). `Repo` keeps one global lock (single instance).
- **Sessions**: 7 days idle, no absolute timeout and no re-authentication for sensitive actions (there are none beyond settings).
