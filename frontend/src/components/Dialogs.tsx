// Small dialogs: new board, announce an idea, confirm.

import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogFooter, AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { api } from '@/api'
import { getLang, t } from '@/i18n'
import type { Board, Idea } from '@/types'
import { Dlg, Field, Sel } from './Field'
import { S, emit, fail, loadBoards, loadTemplates, localName, notify, openBoard, openDialog, useStore } from '@/store'

const closeDialog = () => openDialog(null)

export function NewBoardDialog({ first }: { first: boolean }) {
  const { templates } = useStore()
  const [title, setTitle] = useState('')
  const [goal, setGoal] = useState('')
  const [tpl, setTpl] = useState<number | null>(null)
  useEffect(() => {
    loadTemplates().catch(fail)
  }, [])
  const templateId = tpl ?? templates[0]?.id ?? null
  async function create(e: React.FormEvent) {
    e.preventDefault()
    try {
      const { board } = await api<{ board: Board }>('POST', '/api/boards', { title, goal, templateId, lang: getLang() })
      closeDialog()
      await loadBoards()
      await openBoard(board.id)
    } catch (err) {
      fail(err)
    }
  }
  return (
    <Dlg
      title={t('newBoard')}
      onClose={closeDialog}
      footer={
        <>
          <Button variant="secondary" onClick={closeDialog}>{t('cancel')}</Button>
          <Button type="submit" form="new-board">{t('create')}</Button>
        </>
      }
    >
      <form id="new-board" onSubmit={create}>
        {first ? <p>{t('firstRun')}</p> : null}
        <Field label={t('title')}><Input autoFocus required value={title} placeholder={t('newBoard')} onChange={(e) => setTitle(e.target.value)} /></Field>
        <Field label={t('goal')}><Textarea rows={3} value={goal} placeholder={t('goalPlaceholder')} onChange={(e) => setGoal(e.target.value)} /></Field>
        {templateId != null ? (
          <Field label={t('template')}>
            <Sel value={templateId} onChange={setTpl} items={templates.map((x) => ({ value: x.id, label: localName(x.name) }))} />
          </Field>
        ) : null}
      </form>
    </Dlg>
  )
}

export function AnnounceDialog({ idea }: { idea: Idea }) {
  const { boards, board } = useStore()
  const others = boards.filter((b) => b.id !== board?.id)
  const [picked, setPicked] = useState<Set<number>>(new Set())
  const all = others.length > 0 && others.every((b) => picked.has(b.id))
  const toggle = (id: number, on: boolean) => {
    const next = new Set(picked)
    if (on) next.add(id); else next.delete(id)
    setPicked(next)
  }
  async function announce() {
    const ids = others.filter((b) => picked.has(b.id)).map((b) => b.id)
    if (!ids.length) return
    try {
      const r = await api<{ guests: unknown[] }>('POST', `/api/ideas/${idea.id}/announce`, { boardIds: ids })
      closeDialog()
      notify(r.guests.length ? t('announced', { n: r.guests.length }) : t('announceNone'), r.guests.length ? 'good' : 'info')
    } catch (err) {
      fail(err)
    }
  }
  return (
    <Dlg
      title={t('announceTitle', { t: idea.title })}
      onClose={closeDialog}
      footer={
        <>
          <Button variant="secondary" onClick={closeDialog}>{t('cancel')}</Button>
          {others.length ? <Button onClick={announce}>{t('announce')}</Button> : null}
        </>
      }
    >
      <p className="text-xs text-muted-foreground">{t('announceHint')}</p>
      {others.length ? (
        <>
          <Label className="my-2 cursor-pointer font-normal">
            <Checkbox checked={all} onCheckedChange={(on) => setPicked(on ? new Set(others.map((b) => b.id)) : new Set())} />
            <strong>{t('allBoards')}</strong>
          </Label>
          {others.map((b) => (
            <Label key={b.id} className="my-2 cursor-pointer font-normal">
              <Checkbox checked={picked.has(b.id)} onCheckedChange={(on) => toggle(b.id, on)} />
              {b.title}
              {b.goal ? <span className="text-xs text-muted-foreground">{` · ${b.goal}`}</span> : null}
            </Label>
          ))}
        </>
      ) : <p>{t('noOtherBoards')}</p>}
    </Dlg>
  )
}

export function ConfirmDialog() {
  const { confirm } = useStore()
  const done = (ok: boolean) => {
    confirm?.resolve(ok)
    S.confirm = null
    emit()
  }
  return (
    <AlertDialog open={!!confirm} onOpenChange={(open) => !open && done(false)}>
      <AlertDialogContent>
        <AlertDialogTitle className="text-sm font-normal">{confirm?.text}</AlertDialogTitle>
        <AlertDialogFooter>
          <AlertDialogCancel>{t('cancel')}</AlertDialogCancel>
          <AlertDialogAction variant="destructive" onClick={() => done(true)}>{t('delete')}</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
