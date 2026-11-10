# Idea Generator – frontend

React + TypeScript + Tailwind 4 + shadcn port of the upstream `idea-generator-2` vanilla-JS UI.
Talks to the backend over the same HTTP/SSE contract (`/api/*`, proxied to `http://127.0.0.1:8300` in dev).

```bash
pnpm install
pnpm dev     # http://localhost:5173
pnpm build
pnpm lint
```
