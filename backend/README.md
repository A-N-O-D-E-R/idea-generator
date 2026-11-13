# Idea Generator backend (Java 25 / Spring Boot 4.1)

A port of the `idea-generator-2` Node server. The HTTP contract (routes, bodies, JSON shapes, status codes, `{error, code}` errors, SSE, Markdown exports) is the same as upstream `server/app.ts`.

## Run

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25.0.2-oracle
mvn -o package                              # also runs the tests
java -jar target/idea-generator-0.1.0.jar   # http://127.0.0.1:8300
```

Environment variables:

- `PORT`, `HOST` (default 127.0.0.1).
- `IDEAGEN_AUTH`: `none` (default) or `oauth`. `IDEAGEN_DATA_DIR` (default `data`): with `oauth`, projects go to
  `<dir>/tenants/<tenant>/projects/`. `GITHUB_CLIENT_ID`/`_SECRET`, `GOOGLE_CLIENT_ID`/`_SECRET`: the OAuth apps
  (callbacks `<base>/login/oauth2/code/github` and `/google`); a provider without a client id is not offered, and `oauth`
  without any refuses to start.
- Postgres 17. `mvn spring-boot:run` starts `compose.yaml` (postgres:17-alpine on a random loopback port, data in the
  `idea-generator_ideagen-pg` volume) through `spring-boot-docker-compose`. Anywhere else (`java -jar`), set
  `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`. Flyway owns the schema
  (`src/main/resources/db/migration`).
- `IDEAGEN_KEYSET_FILE`: the Tink keyset that encrypts stored API keys. Create one with
  `java -jar target/idea-generator-0.1.0.jar --generate-keyset=<file>` (written owner-only, never overwritten). Without
  it, an ephemeral keyset is used and a warning is logged: stored keys are lost on restart.
- Provider keys as a fallback for the keys stored in the settings: `OPENROUTER_API_KEY`, `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `AZURE_OPENAI_API_KEY`, `CUSTOM_API_KEY`.
- `ANTHROPIC_BASE_URL`, read the way the SDK reads it.

App settings (keys, base URLs, models per role, folders, budget) are stored per tenant: `GET /api/settings` and
`PUT /api/settings`. Plain settings are JSON in `tenant_settings`; the provider keys and the AI Scientist key are
encrypted with Tink AEAD (AES256-GCM, associated data `tenant:name`) in `tenant_secrets`. A secret is never sent back:
the API answers `{"stored": true|false}`. In a `PUT`, a secret string sets it, `""` deletes it, and anything else keeps it.
With `ideagen.auth=none` the server refuses to start unless `server.address` is a loopback address.

### Modes and security (`config/Security.java`)

- Both modes: CSRF for the SPA (`XSRF-TOKEN` cookie, `X-XSRF-TOKEN` header on every write); errors under `/api` are JSON.
- `none`: every request is the tenant `local`; requests whose `Host` is not a loopback name get 403 `bad_host`.
- `oauth`: Spring Security `oauth2Login` (GitHub OAuth2, Google OIDC), sessions in Postgres (Spring Session JDBC, 7 days
  idle). `/api/**` without a session answers 401 `{"code":"unauthenticated","login":["github",…]}`; static files are public.
  `POST /api/logout` (204). `GET /api/me` gives `{mode}` (and `name`, `email` with `oauth`). The first login of an identity
  `(provider, subject)` creates its tenant with the default templates. Every query is scoped by tenant: another tenant's
  board, idea, lead or template is not found (404; deletes answer `{"ok": false}` as for a missing id). The autopilot runs
  every tenant's auto boards with that tenant's settings and its own USD/day limit.
- `oauth` is a shared server: keys come only from the tenant's settings (no environment fallback), local providers and any
  user-given address that resolves to a loopback, private, link-local or CGNAT address are refused (`private_address`):
  provider base URLs, the AI Scientist address and links to seed from; redirects are not followed for those. Projects are
  written under the tenant's folder and served by `GET /api/ideas/{id}/project.zip` (regular files only, symlinks skipped);
  workspace filing, repo scan and reveal are off. A missing keyset is a startup error.

When `../frontend/dist/` exists, the backend serves it as static files.

## Test

```bash
mvn -o test
```

The tests use a fake LLM, a fake provider server (`com.sun.net.httpserver`) and no network. They need Docker: one
Postgres container (Testcontainers, `TestDb`) is shared by the whole run, and each test class gets its own database in it.

## Calibrate the judge (costs money)

```bash
java -cp target/idea-generator-0.1.0.jar -Dloader.main=bio.anode.ideagenerator.CalibrateJudge \
     org.springframework.boot.loader.launch.PropertiesLauncher [--model provider:model]
```

Keys come from the environment. Re-run it whenever the judge's prompt, schema or model changes. It exits with 1 on FAIL.

## Layout (`bio.anode.ideagenerator`)

Controller → service → repository, wired by Spring (`@RestController`, `@Service`, `@Repository`, Lombok `@RequiredArgsConstructor`).
Controllers only parse the request and call a service; services hold the logic and never see HTTP; repositories hold the SQL
(`JdbcClient` on Postgres, schema `V1__schema.sql`, every query scoped by tenant).

| Package | What |
|---|---|
| `controller` | `Board`/`Idea`/`Lead`/`Report`/`Template`/`SettingsController` (upstream `app.ts`, raw JSON bodies coerced as upstream does, SSE with `ResponseBodyEmitter` and `: ping` every 25 s), `ApiErrors` (`{error, code}`), `CurrentTenant` |
| `service` | `BoardService`, `IdeaService` (seed, expand, combine, announce, spin-off), `JudgeService` (judge, background queue of max 3), `ProjectService`, `LeadService`, `ReportService`, `TemplateService`, `ProviderService`, `SettingsService`, `TenantService`, `EvolutionService` (one autopilot step), `Autopilot` (pure `rateCheck` plus a 1 s scheduler; `idea.autopilot=false` turns it off), `EventBus` (per board, followed over SSE), `Ai` (the LLM with the tenant's settings and spend, and `Ai.Io` for every outside call: chat, fetch, papers, reveal, git, so tests can fake them) |
| `repository` | `Board`/`Idea`/`Lead`/`Template`/`Tenant`/`Spend`/`Report`/`SettingsRepository` |
| `model` | `Board` (with `mergeConfig` and the default templates), `Idea`, `Lead`, `NewIdea`, `Me`, `Reports` |
| `config` | `AppConfig` (beans that need properties), `Mode` (`ideagen.auth`), `Security` (both modes' filter chain: CSRF, loopback Host check in `none`, OAuth login and sessions in `oauth`, `TenantUser`), `ApiDocs` |
| root | `IdeaGeneratorApplication` (`--generate-keyset`), and the pure helpers: `Providers` (`provider:model` routing over `java.net.http`), `Llm` (every prompt and schema), `Leads`, `Project`, `Extract`, `Workspace`, `Scientist`, `Markdown` (`export.md`, `report.md`), `Score`, `Elo`, `Evolution`, `Js` (JSON plus the JS coercions upstream relies on), `Secrets` (Tink AEAD), `AppError`, `CalibrateJudge` |

## Port notes

- **Postgres, not SQLite.** Timestamps stay text (`YYYY-MM-DD HH:MM:SS`, UTC) and JSON columns stay text, so every JSON
  shape is unchanged (jsonb would reorder keys). Repository methods that read, then write the same rows are still `synchronized` (one lock per repository), as
  upstream used a single synchronous handle.
- **Anthropic is called over HTTP**, not through an SDK. The request shape is the upstream SDK call: `output_config` with a JSON schema and `effort: low`, no temperature, and `max_tokens` of at least 4000. The headers follow upstream CLAUDE.md: `anthropic-version: 2026-10-21` and the key as `Authorization: Bearer`. The SDK's retry policy (2 retries on 408/409/429/5xx) and its pagination of `/v1/models` are reproduced. This has not been tested against the live API, only against a fake server, which is also true upstream.
- **Shutdown is immediate** (`server.shutdown: immediate`). Otherwise an open event stream holds a graceful shutdown for 30 s.
- **Static files** are served by Spring's resource handler. An encoded `..` gets a 400 from Tomcat, where upstream answered 404. A missing file returns `{"error":"Not found"}` (404), and a non-GET request returns `{"error":"Method not allowed"}` (405), as upstream.
- **`days` (dashboard and report) is truncated to an integer.** Upstream accepted fractional values such as `7.5`.
- **`idea.json` in project folders** is pretty-printed by Jackson (`"key" : value`) instead of `JSON.stringify(…, null, 2)`. The content is the same.
- **Reveal** uses `xdg-open` (or `open` on macOS). Upstream only used `open`.
