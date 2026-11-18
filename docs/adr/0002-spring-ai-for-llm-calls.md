# 2. Spring AI for the LLM calls

Date: 2026-11-18 — Status: accepted. Completes the "not done here" of ADR 0001.

## Context

`Providers.java` (712 lines) spoke the OpenAI-compatible, Anthropic and Ollama HTTP APIs by hand: request shapes, retries, the fallbacks for models
that refuse a parameter, cost and citation parsing. The other apps use Spring AI (ai-scientist, productDesigner).

## Decision

- The chat calls go through Spring AI 2.0 (`spring-ai-openai`, `spring-ai-anthropic`, `spring-ai-ollama`, versions from the collection BOM). The
  config format stays `provider:model` (`openrouter:x-ai/grok-4.3`, `anthropic:claude-opus-5-5`, `ollama:qwen3:8b`); settings, per-tenant keys
  and base URLs stay as they were. `Providers.makeChat` returns a `SpringAiChat`; everything above it (`Providers.Chat`, the tests' fakes) is unchanged.
- No starters: they autoconfigure one model from properties, and here a model depends on the tenant. `ChatModels` builds a `ChatModel` per
  (provider, base URL, key) on demand and keeps it, so a tenant's key and base URL give the tenant its own model (Spring AI reads both when the
  model is built, not per request).
- OpenAI-compatible providers (OpenRouter, OpenAI, **Azure through its OpenAI-compatible `/openai/v1` endpoint**, LM Studio, MLX, any other)
  all use the OpenAI model: strict `json_schema` output, OpenRouter's `reasoning`, `usage` and `plugins` as extra body, its price from
  `usage.cost` and its search citations from the answer's annotations. A model that refuses `temperature`, wants `max_completion_tokens` or has no
  structured output is adapted and retried without spending an attempt, as before.
- A shared server keeps its guarantees for user-given base URLs: the OpenAI-compatible transport is an OkHttp interceptor that checks the
  address is public on every request, never follows a redirect (Spring AI's client follows them by default: a public host could send the request
  inside) and refuses an answer over 4 MB; Ollama gets the same on its `RestClient`.
- What stays raw HTTP: listing a provider's models (the connection test), and the AI Scientist and productDesigner hand-offs.

## Consequences

- The OpenAI SDK requires the fields a real server always sends (`id`, `object`, `created`, `model`, `finish_reason`); the old code accepted a
  bare `{choices:[{message:{content}}]}`. The tests' fake answers now carry them.
- `ChatModels` never closes a model's HTTP client: the cache is bounded (cleared past 256 entries), enough for the tenants of one server.
- Anthropic's retries are the SDK's own (two, on 408/409/429/5xx and connection errors), as the old code copied them.
- The call timeouts are the models': 150 s for OpenAI-compatible, 300 s for Ollama, 10 minutes for Anthropic.
