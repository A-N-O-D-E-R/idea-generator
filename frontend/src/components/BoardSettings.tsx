// Board settings: general, creation flow (+ templates), jury, scouts, autopilot, models per role.

import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { api } from '@/api'
import { t } from '@/i18n'
import { ROLES } from '@/settings'
import type { BoardConfig, Lang, LeadSource, Template } from '@/types'
import { Dlg, Field, Fieldset, Sel } from './Field'
import {
  S, ask, fail, loadBoards, loadTemplates, localName, notify, openBoard, openDialog, patchBoard, useStore,
} from '@/store'

const closeDialog = () => openDialog(null)

function Check({ checked, onChange, children }: { checked: boolean; onChange: (v: boolean) => void; children: React.ReactNode }) {
  return (
    <Label className="my-2 cursor-pointer font-normal">
      <Checkbox checked={checked} onCheckedChange={onChange} />
      {children}
    </Label>
  )
}

export function BoardSettings() {
  const { board: b, templates, settings } = useStore()
  const [title, setTitle] = useState(b!.title)
  const [goal, setGoal] = useState(b!.goal)
  const [c, setC] = useState<BoardConfig>(() => structuredClone(b!.config))
  const [tpl, setTpl] = useState<number | null>(null)
  useEffect(() => {
    loadTemplates().catch(fail)
  }, [])
  if (!b) return null
  const tplId = tpl != null && templates.some((x) => x.id === tpl) ? tpl : templates[0]?.id ?? null
  const current = templates.find((x) => x.id === tplId)
  const set = (patch: Partial<BoardConfig>) => setC({ ...c, ...patch })
  const setFlow = (k: keyof BoardConfig['flow'], v: string) => set({ flow: { ...c.flow, [k]: v } })
  const setScout = (src: LeadSource, patch: Partial<BoardConfig['researchers'][LeadSource]>) =>
    set({ researchers: { ...c.researchers, [src]: { ...c.researchers[src], ...patch } } })
  const templateBody = { name: c.flowName, system: c.flow.system, expand: c.flow.expand, combine: c.flow.combine }

  async function saveTemplate(method: 'POST' | 'PUT') {
    try {
      await api(method, method === 'POST' ? '/api/templates' : `/api/templates/${tplId}`, templateBody)
      await loadTemplates(true)
      notify(t('templateSaved'))
    } catch (e) {
      fail(e)
    }
  }
  async function deleteTemplate(x: Template) {
    if (!(await ask(t('confirmDeleteTemplate', { t: localName(x.name) })))) return
    try {
      await api('DELETE', `/api/templates/${x.id}`)
      await loadTemplates(true)
      notify(t('templateDeleted'))
    } catch (e) {
      fail(e)
    }
  }
  async function deleteBoard() {
    if (!(await ask(t('confirmDeleteBoard', { t: b!.title })))) return
    try {
      await api('DELETE', `/api/boards/${b!.id}`)
      closeDialog()
      await loadBoards()
      if (S.boards.length) await openBoard(S.boards[0].id)
      else {
        S.board = null
        openDialog({ kind: 'newBoard', first: true })
      }
    } catch (e) {
      fail(e)
    }
  }
  async function save() {
    const board = await patchBoard({
      title, goal,
      config: {
        lang: c.lang, flowName: c.flowName, flow: c.flow,
        judgeGuidance: c.judgeGuidance, autoProjectOnGold: c.autoProjectOnGold, diversity: c.diversity,
        researchers: c.researchers,
        mode: c.mode, autopilotSeconds: Number(c.autopilotSeconds), rate: { stepsPerHour: Number(c.rate.stepsPerHour), usdPerDay: Number(c.rate.usdPerDay) },
        models: Object.fromEntries(ROLES.map((r) => [r, c.models?.[r] ?? ''])),
      },
    })
    if (board) closeDialog()
  }
  const num = (v: string) => (v === '' ? ('' as unknown as number) : Number(v))

  return (
    <Dlg
      title={t('boardSettings')}
      onClose={closeDialog}
      footer={
        <>
          <Button variant="secondary" onClick={closeDialog}>{t('cancel')}</Button>
          <Button onClick={save}>{t('save')}</Button>
        </>
      }
    >
      <Fieldset legend={t('general')}>
        <Field label={t('title')}><Input value={title} onChange={(e) => setTitle(e.target.value)} /></Field>
        <Field label={t('goal')}><Textarea rows={2} value={goal} placeholder={t('goalPlaceholder')} onChange={(e) => setGoal(e.target.value)} /></Field>
        <Field label={t('language')}>
          <Sel<Lang> value={c.lang} onChange={(v) => set({ lang: v })} items={[{ value: 'sv', label: 'Svenska' }, { value: 'en', label: 'English' }]} />
        </Field>
      </Fieldset>
      <Fieldset legend={t('flow')}>
        {tplId != null ? (
          <div className="flex flex-wrap items-center gap-2">
            <Sel className="w-auto min-w-48" value={tplId} onChange={setTpl} items={templates.map((x) => ({ value: x.id, label: localName(x.name) }))} />
            <Button
              variant="secondary"
              size="sm"
              onClick={() => current && setC({ ...c, flowName: current.name, flow: { system: current.system, expand: current.expand, combine: current.combine } })}
            >
              {t('applyTemplate')}
            </Button>
            <Button variant="secondary" size="sm" onClick={() => saveTemplate('PUT')}>{t('updateTemplate')}</Button>
            <Button variant="ghost" size="sm" className="text-destructive" onClick={() => current && deleteTemplate(current)}>{t('deleteTemplate')}</Button>
          </div>
        ) : null}
        <Field label={t('flowName')}><Input value={c.flowName} onChange={(e) => set({ flowName: e.target.value })} /></Field>
        <Field label={t('systemPrompt')}><Textarea rows={3} value={c.flow.system} onChange={(e) => setFlow('system', e.target.value)} /></Field>
        <Field label={t('expandPrompt')}><Textarea rows={3} value={c.flow.expand} onChange={(e) => setFlow('expand', e.target.value)} /></Field>
        <Field label={t('combinePrompt')}><Textarea rows={3} value={c.flow.combine} onChange={(e) => setFlow('combine', e.target.value)} /></Field>
        <Button variant="secondary" size="sm" onClick={() => saveTemplate('POST')}>{t('saveAsTemplate')}</Button>
      </Fieldset>
      <Fieldset legend={t('jury')}>
        <Field label={t('judgeGuidance')}>
          <Textarea rows={2} value={c.judgeGuidance} placeholder={t('judgeGuidancePh')} onChange={(e) => set({ judgeGuidance: e.target.value })} />
        </Field>
        <Check checked={c.autoProjectOnGold} onChange={(v) => set({ autoProjectOnGold: v })}>{t('autoProject')}</Check>
      </Fieldset>
      <Fieldset legend={t('researchers')}>
        <Check checked={c.researchers.x.enabled} onChange={(v) => setScout('x', { enabled: v })}>{t('xScout')}</Check>
        <Field label={t('focus')}>
          <Input value={c.researchers.x.focus} placeholder={t('focusPh')} onChange={(e) => setScout('x', { focus: e.target.value })} />
        </Field>
        <Check checked={c.researchers.research.enabled} onChange={(v) => setScout('research', { enabled: v })}>{t('researchScout')}</Check>
        <Field label={t('focus')}>
          <Input value={c.researchers.research.focus} placeholder={t('focusPh')} onChange={(e) => setScout('research', { focus: e.target.value })} />
        </Field>
      </Fieldset>
      <Fieldset legend={t('autopilot')}>
        <Field label={t('mode')}>
          <Sel<BoardConfig['mode']> value={c.mode} onChange={(v) => set({ mode: v })} items={[{ value: 'manual', label: t('manual') }, { value: 'auto', label: t('auto') }]} />
        </Field>
        <div className="grid grid-cols-3 gap-x-3">
          <Field label={t('pace')}>
            <Input type="number" min={3} max={3600} value={c.autopilotSeconds} onChange={(e) => set({ autopilotSeconds: num(e.target.value) })} />
          </Field>
          <Field label={t('stepsPerHour')}>
            <Input type="number" min={1} max={3600} value={c.rate.stepsPerHour} onChange={(e) => set({ rate: { ...c.rate, stepsPerHour: num(e.target.value) } })} />
          </Field>
          <Field label={t('usdPerDay')}>
            <Input type="number" min={0} step={0.1} value={c.rate.usdPerDay} onChange={(e) => set({ rate: { ...c.rate, usdPerDay: num(e.target.value) } })} />
          </Field>
        </div>
        <Check checked={c.diversity} onChange={(v) => set({ diversity: v })}>{t('diversity')}</Check>
        <small className="text-muted-foreground">{t('rateNote')}</small>
      </Fieldset>
      <Fieldset legend={t('models')}>
        <small className="text-muted-foreground">{t('modelsBoardNote')}</small>
        <div className="grid grid-cols-2 gap-x-3">
          {ROLES.map((r) => (
            <Field key={r} label={t(`role_${r}`)}>
              <Input
                list="model-list"
                value={c.models?.[r] ?? ''}
                placeholder={settings.models[r]}
                onChange={(e) => set({ models: { ...c.models, [r]: e.target.value } })}
              />
            </Field>
          ))}
        </div>
      </Fieldset>
      <Button variant="outline" size="sm" className="border-[#4a2a2e] bg-transparent text-destructive dark:border-[#4a2a2e] dark:bg-transparent" onClick={deleteBoard}>
        {t('deleteBoard')}
      </Button>
    </Dlg>
  )
}
