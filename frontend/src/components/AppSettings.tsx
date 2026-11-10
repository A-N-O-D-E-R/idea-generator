// App settings: API keys, provider addresses, models per role, projects folder, global budget.
// Stored in localStorage and pushed to the server (PUT /api/runtime-settings).

import { useState } from 'react'
import { cn } from '@/lib/utils'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { api } from '@/api'
import { errorText, t } from '@/i18n'
import { DEFAULTS, PROVIDER_IDS, ROLES, saveSettings } from '@/settings'
import type { AppSettings as Settings, ProviderId } from '@/types'
import { Dlg, Field, Fieldset } from './Field'
import { S, emit, fail, notify, openDialog, useStore, warnMissingKeys } from '@/store'

const PROVIDER_LABELS: Record<ProviderId, string> = {
  openrouter: 'OpenRouter', anthropic: 'Anthropic', openai: 'OpenAI',
  azure: 'Azure OpenAI / Microsoft Foundry (Copilot models)', ollama: 'Ollama', mlx: 'MLX (mlx_lm.server)', lmstudio: 'LM Studio', custom: 'OpenAI-compatible',
}
const HAS_URL: ProviderId[] = ['ollama', 'mlx', 'lmstudio', 'azure', 'custom']
const HAS_KEY: ProviderId[] = ['openrouter', 'anthropic', 'openai', 'azure', 'custom']

/** <datalist> of models found with the Test buttons, for every model field. */
export function ModelList() {
  const { modelCatalog } = useStore()
  return (
    <datalist id="model-list">
      {[...modelCatalog.entries()].flatMap(([p, ids]) => ids.slice(0, 400).map((id) => <option key={`${p}:${id}`} value={`${p}:${id}`} />))}
    </datalist>
  )
}

export function AppSettings() {
  const { settings, providers } = useStore()
  const [s, setS] = useState<Settings>(() => structuredClone(settings))
  const [budget, setBudget] = useState(String(settings.maxUsdPerDay))
  const [results, setResults] = useState<Partial<Record<ProviderId, string>>>({})
  const status = new Map((providers?.providers ?? []).map((p) => [p.id, p]))
  const spend = providers ? t('spend', { today: providers.spentToday.toFixed(3), total: providers.spentTotal.usd.toFixed(3) }) : ''

  async function saveFromForm(close = true) {
    const out: Settings = structuredClone(s)
    for (const p of PROVIDER_IDS) {
      if (HAS_KEY.includes(p)) {
        const k = (out.keys[p] ?? '').trim()
        if (k) out.keys[p] = k
        else delete out.keys[p]
      }
      if (HAS_URL.includes(p)) out.baseUrls[p] = (out.baseUrls[p] ?? '').trim()
    }
    for (const r of ROLES) out.models[r] = out.models[r].trim() || DEFAULTS.models[r]
    out.projectsRoot = out.projectsRoot.trim() || DEFAULTS.projectsRoot
    out.workspaceRoot = out.workspaceRoot.trim()
    out.maxUsdPerDay = Number(budget)
    S.settings = out
    S.providers = await saveSettings(out)
    emit()
    if (close) {
      openDialog(null)
      notify(t('settingsSaved'))
      warnMissingKeys()
    }
  }

  async function test(p: ProviderId) {
    setResults((r) => ({ ...r, [p]: '…' }))
    try {
      await saveFromForm(false)
      const { models } = await api<{ models: string[] }>('POST', `/api/providers/${p}/models`, {})
      S.modelCatalog.set(p, models)
      emit()
      setResults((r) => ({ ...r, [p]: t('testOk', { n: models.length }) }))
    } catch (e) {
      setResults((r) => ({ ...r, [p]: errorText(e) }))
    }
  }

  return (
    <Dlg
      title={t('appSettings')}
      onClose={() => openDialog(null)}
      footer={
        <>
          <Button variant="secondary" onClick={() => openDialog(null)}>{t('cancel')}</Button>
          <Button onClick={() => saveFromForm().catch(fail)}>{t('save')}</Button>
        </>
      }
    >
      <p className="text-xs text-muted-foreground">{t('settingsNote')}</p>
      <Fieldset legend={t('providers')}>
        {PROVIDER_IDS.map((p, i) => {
          const st = status.get(p)
          return (
            <div key={p} className={cn('py-2.5', i > 0 && 'border-t')}>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <strong>{PROVIDER_LABELS[p]}</strong>
                <Badge
                  variant="outline"
                  className={cn('text-subtle', st?.keySource ? 'border-[#2f6a55] text-[#a6e6cd]' : !st?.local && 'border-[#6b5521] text-gold')}
                >
                  {st?.local ? t('local') : st?.keySource ? t(`keyFrom_${st.keySource}`) : t('keyMissing')}
                </Badge>
              </div>
              {st?.note ? <small className="text-muted-foreground">{st.note}</small> : null}
              <div className="mt-1 flex flex-wrap items-center gap-2">
                {HAS_KEY.includes(p) ? (
                  <Input
                    type="password"
                    autoComplete="off"
                    className="w-auto min-w-45 flex-1"
                    placeholder={t('apiKey')}
                    value={s.keys[p] ?? ''}
                    onChange={(e) => setS({ ...s, keys: { ...s.keys, [p]: e.target.value } })}
                  />
                ) : null}
                {HAS_URL.includes(p) ? (
                  <Input
                    className="w-auto min-w-45 flex-1"
                    placeholder={t('baseUrl')}
                    value={s.baseUrls[p] ?? ''}
                    onChange={(e) => setS({ ...s, baseUrls: { ...s.baseUrls, [p]: e.target.value } })}
                  />
                ) : null}
                <Button variant="secondary" size="sm" onClick={() => test(p)}>{t('test')}</Button>
                <span className="text-xs text-muted-foreground">{results[p]}</span>
              </div>
            </div>
          )
        })}
      </Fieldset>
      <Fieldset legend={t('models')}>
        <div className="grid grid-cols-2 gap-x-3">
          {ROLES.map((r) => (
            <Field key={r} label={t(`role_${r}`)}>
              <Input
                list="model-list"
                value={s.models[r]}
                placeholder={DEFAULTS.models[r]}
                onChange={(e) => setS({ ...s, models: { ...s.models, [r]: e.target.value } })}
              />
            </Field>
          ))}
        </div>
        <small className="text-muted-foreground">{t('calibrateNote')}</small>
      </Fieldset>
      <Fieldset legend="Claude Code">
        <Field label={t('projectsRoot')}>
          <Input value={s.projectsRoot} onChange={(e) => setS({ ...s, projectsRoot: e.target.value })} />
        </Field>
        <Field label={t('workspaceRoot')} note={t('workspaceNote')}>
          <Input value={s.workspaceRoot} placeholder="~/Documents/Code/Idea" onChange={(e) => setS({ ...s, workspaceRoot: e.target.value })} />
        </Field>
      </Fieldset>
      <Fieldset legend={t('autopilot')}>
        <Field label={t('globalBudget')} note={spend}>
          <Input type="number" min={0} step={0.5} value={budget} onChange={(e) => setBudget(e.target.value)} />
        </Field>
      </Fieldset>
    </Dlg>
  )
}
