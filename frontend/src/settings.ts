// App settings live in this browser (localStorage): API keys, provider
// addresses, the model for each role, the projects folder, the global budget.
// They are pushed to the local server, which holds them in memory only.

import { api } from './api'
import type { AppSettings, ProviderId, ProvidersResponse, Role } from './types'

const KEY = 'ideagen.settings.v1'
export const ROLES: Role[] = ['distill', 'expand', 'combine', 'judge', 'summary', 'research', 'x', 'brief']
export const PROVIDER_IDS: ProviderId[] = ['openrouter', 'anthropic', 'openai', 'azure', 'ollama', 'mlx', 'lmstudio', 'custom']

const SONNET = 'openrouter:anthropic/claude-sonnet-5.5'
export const DEFAULTS: AppSettings = {
  keys: {},
  baseUrls: { ollama: 'http://localhost:11434', mlx: 'http://localhost:8080/v1', lmstudio: 'http://localhost:1234/v1', azure: '', custom: '' },
  models: { distill: SONNET, expand: SONNET, combine: SONNET, judge: SONNET, summary: SONNET, research: SONNET, x: 'openrouter:x-ai/grok-4.3', brief: SONNET },
  projectsRoot: '~/Projekt/idea-projects',
  maxUsdPerDay: 5,
  workspaceRoot: '',
  scientistUrl: '',
  scientistKey: '',
}

export function loadSettings(): AppSettings {
  let saved: Partial<AppSettings> = {}
  try {
    saved = JSON.parse(localStorage.getItem(KEY) || '{}')
  } catch {
    /* corrupt: start from defaults */
  }
  return {
    keys: { ...DEFAULTS.keys, ...saved.keys },
    baseUrls: { ...DEFAULTS.baseUrls, ...saved.baseUrls },
    models: { ...DEFAULTS.models, ...saved.models },
    projectsRoot: saved.projectsRoot || DEFAULTS.projectsRoot,
    maxUsdPerDay: Number.isFinite(saved.maxUsdPerDay) ? saved.maxUsdPerDay! : DEFAULTS.maxUsdPerDay,
    workspaceRoot: saved.workspaceRoot ?? DEFAULTS.workspaceRoot,
    scientistUrl: saved.scientistUrl ?? DEFAULTS.scientistUrl,
    scientistKey: saved.scientistKey ?? DEFAULTS.scientistKey,
  }
}

/** Push to the server; returns provider status (which keys it has, where from). */
export function pushSettings(s: AppSettings) {
  return api<ProvidersResponse>('PUT', '/api/runtime-settings', s)
}

export async function saveSettings(s: AppSettings) {
  localStorage.setItem(KEY, JSON.stringify(s))
  return pushSettings(s)
}

/** Providers the current models depend on, so we can warn about a missing key. */
export function providersInUse(s: AppSettings) {
  return [...new Set(ROLES.map((r) => providerOf(s.models[r])))]
}

export function providerOf(ref = ''): ProviderId {
  const i = ref.indexOf(':')
  const head = i > 0 ? ref.slice(0, i) : ''
  return (PROVIDER_IDS as string[]).includes(head) ? (head as ProviderId) : 'openrouter'
}
