// App settings live on the server, per account: API keys (encrypted there, never sent back),
// provider addresses, the model for each role, the projects folder, the global budget.

import { api } from './api'
import type { AppSettings, ProviderId, Role, SettingsResponse, SettingsUpdate } from './types'

/** Where settings (keys included) lived before they moved to the server. */
const LEGACY_KEY = 'ideagen.settings.v1'
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
  scientistKey: { stored: false },
  reposRoots: '',
}

/**
 * The settings from the server. Settings (and keys) still in this browser from before are
 * handed over once, then removed from localStorage.
 */
export async function loadSettings(): Promise<SettingsResponse> {
  const legacy = localStorage.getItem(LEGACY_KEY)
  if (legacy) {
    let saved: unknown = {}
    try {
      saved = JSON.parse(legacy)
    } catch {
      /* corrupt: nothing to hand over */
    }
    const res = await saveSettings((saved && typeof saved === 'object' ? saved : {}) as SettingsUpdate)
    localStorage.removeItem(LEGACY_KEY)
    return res
  }
  return api<SettingsResponse>('GET', '/api/settings')
}

export function saveSettings(s: SettingsUpdate) {
  return api<SettingsResponse>('PUT', '/api/settings', s)
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
