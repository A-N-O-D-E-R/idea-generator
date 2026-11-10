# Idea Generator backend (Java 25 / Spring Boot 4.1)

A port of the `idea-generator-2` Node server. The HTTP contract (routes, bodies, JSON shapes, status codes, `{error, code}` errors, SSE, Markdown exports) is the same as upstream `server/app.ts`.

## Run

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25.0.2-oracle
mvn -o package                              # also runs the tests
java -jar target/idea-generator-0.1.0.jar   # http://127.0.0.1:8300
```

Environment variables:

- `PORT`, `HOST`.
- Postgres 17. `mvn spring-boot:run` starts `compose.yaml` (postgres:17-alpine on a random loopback port, data in the
  `idea-generator_ideagen-pg` volume) through `spring-boot-docker-compose`. Anywhere else (`java -jar`), set
  `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`. Flyway owns the schema
  (`src/main/resources/db/migration`).
- Provider keys as a fallback for the keys the browser pushes: `OPENROUTER_API_KEY`, `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `AZURE_OPENAI_API_KEY`, `CUSTOM_API_KEY`.
- `ANTHROPIC_BASE_URL`, read the way the SDK reads it.

App settings (keys, base URLs, models per role) come from the browser through `PUT /api/runtime-settings`. They are kept in memory only.

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

## Layout (`bio.anode.ideagenerator`, one flat package)

| File | Upstream | What |
|---|---|---|
| `Api` | `app.ts` | Every route, SSE (`ResponseBodyEmitter`, raw `data: …\n\n` framing, `: ping` every 25 s), error advice |
| `Markdown` | `app.ts` | `export.md` and `report.md` |
| `Engine` | `engine.ts` | Operations, background judge queue (max 3), per-board `Bus`, `judging` set, `autoStep`. `Engine.Io` holds every outside call (chat, fetch, papers, reveal, git), so tests can fake them |
| `Autopilot` | `autopilot.ts` | Pure `rateCheck`, plus a 1 s scheduler on a virtual thread that starts with the app (`idea.autopilot=false` turns it off) |
| `Repo` | `db.ts` | `JdbcClient` on Postgres, with the upstream schema (`V1__schema.sql`), seed templates per tenant and queries, every one scoped by tenant. `report()` and `dashboard()` live here too |
| `Board`, `Idea` | `db.ts` | Records, `mergeConfig` and the default templates |
| `Providers` | `providers.ts` | `provider:model` routing over `java.net.http`: OpenAI-compatible, Ollama native, and the Anthropic Messages API |
| `Llm` | `llm.ts` | Every prompt and schema |
| `Leads`, `Project`, `Extract` | same names | The X and research scouts, the Claude Code project folders, and link extraction |
| `Score`, `Evolution` | `public/js/score.js`, `evolve.js` | Pure ports |
| `Js` | – | JSON (Jackson 3) plus the JS coercions upstream relies on (`String()`, `Number()`, `trim`, `slice`) |
| `RuntimeSettings`, `IdeaGeneratorApplication` | `index.ts` | In-memory settings and wiring |
| `CalibrateJudge` | `tools/calibrate-judge.ts` | The calibration tool |

## Port notes

- **Postgres, not SQLite.** Timestamps stay text (`YYYY-MM-DD HH:MM:SS`, UTC) and JSON columns stay text, so every JSON
  shape is unchanged (jsonb would reorder keys). Every `Repo` method is still `synchronized`, as upstream used a single
  synchronous handle.
- **Anthropic is called over HTTP**, not through an SDK. The request shape is the upstream SDK call: `output_config` with a JSON schema and `effort: low`, no temperature, and `max_tokens` of at least 4000. The headers follow upstream CLAUDE.md: `anthropic-version: 2026-10-21` and the key as `Authorization: Bearer`. The SDK's retry policy (2 retries on 408/409/429/5xx) and its pagination of `/v1/models` are reproduced. This has not been tested against the live API, only against a fake server, which is also true upstream.
- **Shutdown is immediate** (`server.shutdown: immediate`). Otherwise an open event stream holds a graceful shutdown for 30 s.
- **Static files** are served by Spring's resource handler. An encoded `..` gets a 400 from Tomcat, where upstream answered 404. A missing file returns `{"error":"Not found"}` (404), and a non-GET request returns `{"error":"Method not allowed"}` (405), as upstream.
- **`days` (dashboard and report) is truncated to an integer.** Upstream accepted fractional values such as `7.5`.
- **`idea.json` in project folders** is pretty-printed by Jackson (`"key" : value`) instead of `JSON.stringify(…, null, 2)`. The content is the same.
- **Reveal** uses `xdg-open` (or `open` on macOS). Upstream only used `open`.
