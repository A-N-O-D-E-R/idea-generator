# Idea Generator

Java port of [fltman/idea-generator-2](https://github.com/fltman/idea-generator-2) (MIT, see `LICENSE.upstream`):
an idea board where language models branch, cross and judge ideas, and an evolution autopilot breeds the best ones.

| Folder | Stack |
|---|---|
| `backend/` | Java 25, Spring Boot 4.1, SQLite (JdbcClient), `java.net.http` providers. Same HTTP/SSE API as upstream. |
| `frontend/` | TypeScript, React 19, Vite 7, Tailwind 4, shadcn. Same UI as upstream. |

## Run

```bash
pnpm backend    # http://127.0.0.1:8300 (API)
pnpm frontend   # http://localhost:5173, proxies /api to the backend
```

Single process: `pnpm build`, then `cd backend && java -jar target/idea-generator-0.1.0.jar` serves `../frontend/dist` too.

Needs Java 25 (scripts use `~/.sdkman/candidates/java/25.0.2-oracle`), Node 20.19+, pnpm.
Vite stays on 7: Vite 8's native bundler needs AVX2.

The server listens on 127.0.0.1 only. There is no login and API keys pass through it: **do not expose it** as is.

## Additions over upstream

- **Idea workspace filing.** Set *App settings › Idea workspace* to a folder that has an `INDEX.md` (e.g. `~/Documents/Code/Idea`).
  Every Claude Code project, made by hand or by the judge on gold, is then also filed there as `ideas/<slug>/PITCH.md` + `LINK.md`,
  with a `raw` row added at the top of the `INDEX.md` table. It reuses the project brief (no extra LLM call) and never overwrites:
  a slug already in `ideas/` or in `INDEX.md` is left alone. Code: `backend/.../Workspace.java`.
- **Duplicate check before filing.** One call to the board's `brief` model compares the brief with the `INDEX.md` rows.
  A duplicate (same problem, same mechanism) is not filed; the toast names the existing idea. Up to 4 related ideas are linked
  both ways in their `LINK.md`. Slugs the model invents are dropped. An empty index costs no call.
- **Elo ranking.** The 1–5 rubric saturates among strong ideas, so *Duel* (board overview) compares two of the 8 best judged
  ideas head to head with the board's judge model, twice with the order swapped; answers that disagree are a draw (against
  position bias). Elo starts at 1000, K = 32; the fewest-duelled ideas are picked first. Elo is shown, not yet used by evolution.
  `POST /api/boards/{id}/duel`. Code: `backend/.../Elo.java`.
