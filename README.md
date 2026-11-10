# Idea Generator

Java port of [fltman/idea-generator-2](https://github.com/fltman/idea-generator-2) (MIT, see `LICENSE.upstream`):
an idea board where language models branch, cross and judge ideas, and an evolution autopilot breeds the best ones.

| Folder | Stack |
|---|---|
| `backend/` | Java 25, Spring Boot 4.1, Postgres + Flyway (JdbcClient), `java.net.http` providers. Same HTTP/SSE API as upstream. |
| `frontend/` | TypeScript, React 19, Vite 7, Tailwind 4, shadcn. Same UI as upstream. |

## Run

```bash
pnpm backend    # http://127.0.0.1:8300 (API)
pnpm frontend   # http://localhost:5173, proxies /api to the backend
```

`pnpm backend` starts Postgres from `backend/compose.yaml` (Docker). Single process: `pnpm build`, then
`cd backend && java -jar target/idea-generator-0.1.0.jar` (with `SPRING_DATASOURCE_URL`/`_USERNAME`/`_PASSWORD`) serves `../frontend/dist` too.

Needs Docker, Java 25 (scripts use `~/.sdkman/candidates/java/25.0.2-oracle`), Node 20.19+, pnpm.
Vite stays on 7: Vite 8's native bundler needs AVX2.

### Two modes (`IDEAGEN_AUTH`)

- **`none`** (default): the local single-user tool, one implicit account. No login, so the server refuses to start unless
  it listens on a loopback address, and it only answers requests addressed to `localhost`/`127.0.0.1` (against DNS rebinding).
  Workspace filing, repo scan, project folders and *Show in Finder* work as before.
- **`oauth`**: a shared server. Sign in with GitHub or Google; each login is its own account with its own boards, ideas,
  templates, settings and encrypted API keys. No environment keys, no local folders: projects are written under
  `IDEAGEN_DATA_DIR/tenants/<id>/projects/` and downloaded as a zip. See `docs/MULTI-USER.md` and `backend/README.md`.

```bash
# oauth, once: a keyset for the stored API keys (keep it secret, back it up)
java -jar backend/target/idea-generator-0.1.0.jar --generate-keyset=/srv/ideagen/keyset.json
IDEAGEN_AUTH=oauth IDEAGEN_KEYSET_FILE=/srv/ideagen/keyset.json IDEAGEN_DATA_DIR=/srv/ideagen/data HOST=0.0.0.0 \
  GITHUB_CLIENT_ID=… GITHUB_CLIENT_SECRET=… GOOGLE_CLIENT_ID=… GOOGLE_CLIENT_SECRET=… \
  SPRING_DATASOURCE_URL=jdbc:postgresql://db/ideagen SPRING_DATASOURCE_USERNAME=… SPRING_DATASOURCE_PASSWORD=… \
  SERVER_FORWARD_HEADERS_STRATEGY=native java -jar backend/target/idea-generator-0.1.0.jar   # behind a TLS proxy
```

App settings (keys included) are stored on the server per account; keys are encrypted with Tink and never sent back to the
browser. Keys left in `localStorage` by an older version are handed over once and removed.

## Additions over upstream

- **Idea workspace filing.** Set *App settings › Idea workspace* to a folder that has an `INDEX.md` (e.g. `~/Documents/Code/Idea`).
  Every Claude Code project, made by hand or by the judge on gold, is then also filed there as `ideas/<slug>/PITCH.md` + `LINK.md`,
  with a `raw` row added at the top of the `INDEX.md` table. It reuses the project brief (no extra LLM call) and never overwrites:
  a slug already in `ideas/` or in `INDEX.md` is left alone. Code: `backend/.../Workspace.java`.
- **Duplicate check before filing.** One call to the board's `brief` model compares the brief with the `INDEX.md` rows.
  A duplicate (same problem, same mechanism) is not filed; the toast names the existing idea. Up to 4 related ideas are linked
  both ways in their `LINK.md`. Slugs the model invents are dropped. An empty index costs no call.
  The same call also gets your code repos (*App settings › Code folders*: sub-folders of each folder, first prose of their
  README, max 150) and up to 10 Hugging Face papers found for the brief. Repos with reusable work go to `EXISTING-ASSETS.md`,
  real prior work to a *Prior work found (unverified)* section of `PITCH.md`. Paths and paper ids it wasn't shown are dropped.
  Note: the paper search sends the brief's name and one-liner to huggingface.co (as the research scout does with the goal).
- **Elo ranking.** The 1–5 rubric saturates among strong ideas, so *Duel* (board overview) compares two of the 8 best judged
  ideas head to head with the board's judge model, twice with the order swapped; answers that disagree are a draw (against
  position bias). Elo starts at 1000, K = 32; the fewest-duelled ideas are picked first. Elo is shown, not yet used by evolution.
  `POST /api/boards/{id}/duel`. Code: `backend/.../Elo.java`.
- **Diversity (fitness sharing).** LLM ideation converges on one family. With *Board settings › Autopilot › Keep several idea
  families alive* (on by default), the autopilot divides each idea's fitness by how crowded its niche is: the sum of
  `1 - d/0.7` over ideas within distance 0.7, where `d = 1 - Jaccard` of the judge's keywords. Near-copies share their fitness,
  a lone niche keeps its own. Off = upstream selection. Code: `Evolution.sharedFitness`.
- **Hand-off to AI Scientist.** With *App settings › AI Scientist* (address + personal `ais_…` key from
  `perso/ai-scientist`, stored encrypted like the provider keys; env fallback `AI_SCIENTIST_API_KEY` in `none` mode only), the idea panel gets *To AI Scientist*:
  it submits `POST /v1/ideations` with a topic built from the idea (Title, Keywords, TL;DR, Abstract; no LLM call here),
  `maxGenerations` 3 and a $2 budget on the ai-scientist account. Experiment runs are started there. Code: `Scientist.java`.
