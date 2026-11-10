// App settings: API keys, provider addresses, models per role, projects folder, global budget.
// Stored on the server (GET/PUT /api/settings). Keys only go in: the server answers {stored} for them.

import { useState } from 'react'
import { cn } from '@/lib/utils'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { api } from '@/api'
import { errorText, t } from '@/i18n'
import { DEFAULTS, PROVIDER_IDS, ROLES, saveSettings } from '@/settings'
import type { AppSettings as Settings, ProviderId, SettingsUpdate } from '@/types'
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
  // New key values typed here ('scientist' = the AI Scientist key); empty = keep the stored one.
  const [typed, setTyped] = useState<Record<string, string>>({})
  const [drop, setDrop] = useState<Set<string>>(new Set())
  const [budget, setBudget] = useState(String(settings.maxUsdPerDay))
  const [results, setResults] = useState<Partial<Record<ProviderId, string>>>({})
  const status = new Map((providers?.providers ?? []).map((p) => [p.id, p]))
  const spend = providers ? t('spend', { today: providers.spentToday.toFixed(3), total: providers.spentTotal.usd.toFixed(3) }) : ''

  /** A typed value sets the secret, a removed one is sent as '' (delete), anything else is left out (keep). */
  const secret = (name: string) => (drop.has(name) ? '' : (typed[name] ?? '').trim() || undefined)

  async function saveFromForm(close = true) {
    const { keys: _stored, scientistKey: _scientist, ...plain } = structuredClone(s)
    const out: SettingsUpdate = { ...plain, keys: {} }
    for (const p of PROVIDER_IDS) {
      if (HAS_KEY.includes(p)) {
        const k = secret(p)
        if (k !== undefined) out.keys![p] = k
      }
      if (HAS_URL.includes(p)) out.baseUrls[p] = (out.baseUrls[p] ?? '').trim()
    }
    for (const r of ROLES) out.models[r] = out.models[r].trim() || DEFAULTS.models[r]
    out.projectsRoot = out.projectsRoot.trim() || DEFAULTS.projectsRoot
    out.workspaceRoot = out.workspaceRoot.trim()
    out.scientistUrl = out.scientistUrl.trim().replace(/\/+$/, '')
    const sk = secret('scientist')
    if (sk !== undefined) out.scientistKey = sk
    out.reposRoots = out.reposRoots.trim()
    out.maxUsdPerDay = Number(budget)
    const res = await saveSettings(out)
    S.providers = res
    S.settings = res.settings
    setS((cur) => ({ ...cur, keys: res.settings.keys, scientistKey: res.settings.scientistKey }))
    setTyped({})
    setDrop(new Set())
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
                  <KeyInput name={p} stored={!!s.keys[p]?.stored} typed={typed} setTyped={setTyped} drop={drop} setDrop={setDrop} />
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
        <Field label={t('reposRoots')} note={t('reposNote')}>
          <Textarea rows={2} value={s.reposRoots} placeholder="~/Documents/Code/perso" onChange={(e) => setS({ ...s, reposRoots: e.target.value })} />
        </Field>
      </Fieldset>
      <Fieldset legend="AI Scientist">
        <small className="text-muted-foreground">{t('scientistNote')}</small>
        <Field label={t('scientistUrl')}>
          <Input value={s.scientistUrl} placeholder="http://localhost:8080" onChange={(e) => setS({ ...s, scientistUrl: e.target.value })} />
        </Field>
        <Field label={t('apiKey')}>
          <div className="flex flex-wrap items-center gap-2">
            <KeyInput name="scientist" stored={s.scientistKey.stored} placeholder="ais_…" typed={typed} setTyped={setTyped} drop={drop} setDrop={setDrop} />
          </div>
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

/** A write-only key field: shows whether a key is stored, never its value. */
function KeyInput(props: {
  name: string
  stored: boolean
  placeholder?: string
  typed: Record<string, string>
  setTyped: (f: (t: Record<string, string>) => Record<string, string>) => void
  drop: Set<string>
  setDrop: (d: Set<string>) => void
}) {
  const { name, stored, typed, setTyped, drop, setDrop } = props
  const dropped = drop.has(name)
  return (
    <>
      <Input
        type="password"
        autoComplete="off"
        className="w-auto min-w-45 flex-1"
        placeholder={dropped ? t('keyRemoved') : stored ? t('keyStored') : (props.placeholder ?? t('apiKey'))}
        value={typed[name] ?? ''}
        onChange={(e) => {
          const v = e.target.value
          setTyped((cur) => ({ ...cur, [name]: v }))
          if (dropped) setDrop(new Set([...drop].filter((x) => x !== name)))
        }}
      />
      {stored && !dropped ? (
        <Button variant="ghost" size="sm" onClick={() => setDrop(new Set([...drop, name]))}>{t('removeKey')}</Button>
      ) : null}
    </>
  )
}
