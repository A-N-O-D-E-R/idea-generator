// App state and actions (port of upstream app.js). One mutable store, like
// upstream's `S`; components re-render through useStore() after emit().

import { useSyncExternalStore } from 'react'
import { toast } from 'sonner'
import { api, download, follow } from './api'
import { errorText, getLang, t } from './i18n'
import type { Graph } from './lib/graph'
import { medal } from './lib/score'
import { loadSettings, providersInUse, pushSettings } from './settings'
import type {
  AppSettings, AutopilotStatus, Board, BoardData, BoardEvent, Edge, Idea, Lead, LeadSource,
  ProvidersResponse, Spinoff, Template, WorkspaceResult,
} from './types'

export const MEDAL_ICON = ['–', '🥉', '🥈', '🥇']

export type DialogState =
  | { kind: 'newBoard'; first: boolean }
  | { kind: 'boardSettings' }
  | { kind: 'appSettings' }
  | { kind: 'announce'; idea: Idea }
  | null
export type View = { kind: 'dashboard'; keyword: string | null } | { kind: 'report' } | null

export const S = {
  boards: [] as Board[],
  board: null as Board | null,
  ideas: new Map<number, Idea>(),
  edges: [] as Edge[],
  leads: [] as Lead[],
  spinoffs: [] as Spinoff[],
  judging: new Set<number>(),
  autopilot: { state: 'manual' } as AutopilotStatus,
  spentToday: 0,
  selected: null as number | null,
  hover: null as number | null,
  templates: [] as Template[],
  providers: null as ProvidersResponse | null,
  settings: loadSettings() as AppSettings,
  stopFollow: null as (() => void) | null,
  createAt: null as { x: number; y: number } | null,
  leadsOpen: false,
  leadsTab: 'x' as LeadSource,
  refreshing: {} as Partial<Record<LeadSource, number>>,
  exhibit: false,
  dialog: null as DialogState,
  confirm: null as { text: string; resolve: (ok: boolean) => void } | null,
  view: null as View,
  /** provider id → model ids, for the model <datalist>. */
  modelCatalog: new Map<string, string[]>(),
}

// ---------------------------------------------------------------- re-render plumbing

let version = 0
const subs = new Set<() => void>()
export function emit() {
  version++
  for (const fn of subs) fn()
}
const subscribe = (fn: () => void) => {
  subs.add(fn)
  return () => subs.delete(fn)
}
export function useStore() {
  useSyncExternalStore(subscribe, () => version)
  return S
}

// ---------------------------------------------------------------- helpers

export function notify(message: string, kind: 'info' | 'good' | 'error' = 'info', ms = 5000) {
  const fn = kind === 'error' ? toast.error : kind === 'good' ? toast.success : toast
  fn(message, { duration: ms })
}
export const fail = (e: unknown) => notify(errorText(e), 'error', 8000)

/** Built-in template names are bilingual ("Uppfinnaren · Inventor"); show the half that matches the UI. */
export const localName = (name: string) => {
  const m = /^(.+?) · (.+)$/.exec(name)
  return m ? (getLang() === 'en' ? m[2] : m[1]) : name
}
export const ideaList = () => [...S.ideas.values()]

/** Promise-based confirm, rendered by <ConfirmDialog>. */
export function ask(text: string) {
  return new Promise<boolean>((resolve) => {
    S.confirm = { text, resolve }
    emit()
  })
}

export function openDialog(d: DialogState) {
  S.dialog = d
  emit()
}

export async function loadTemplates(force = false) {
  if (force || !S.templates.length) S.templates = (await api<{ templates: Template[] }>('GET', '/api/templates')).templates
  emit()
  return S.templates
}

// ---------------------------------------------------------------- canvas

/** The canvas, set by <Board> when it mounts. */
export let graph: Graph | null = null
export const setGraph = (g: Graph | null) => {
  graph = g
}

/** The seed input, set by <SeedBar>. */
export let seedInput: HTMLInputElement | null = null
export const setSeedInput = (el: HTMLInputElement | null) => {
  seedInput = el
}

function debounce<A extends unknown[]>(fn: (...a: A) => void, ms: number) {
  let tm: ReturnType<typeof setTimeout>
  return (...a: A) => {
    clearTimeout(tm)
    tm = setTimeout(() => fn(...a), ms)
  }
}

export function selectIdea(id: number | null) {
  S.selected = id
  graph?.select(id)
  emit()
}

export const graphHandlers = {
  select: selectIdea,
  hover(id: number | null) {
    S.hover = id
    emit()
  },
  combine: (a: number, b: number) => combine(a, b),
  expand: (id: number) => expand(id),
  create(x: number, y: number) {
    S.createAt = { x, y }
    seedInput?.focus()
  },
  moved: debounce(() => savePositions(), 1200),
  dropLead: (id: number, x: number, y: number) => seedLead(id, x, y),
}

async function savePositions() {
  if (!S.board || !graph) return
  try {
    await api('PUT', `/api/boards/${S.board.id}/positions`, { positions: graph.positions() })
  } catch (e) {
    console.warn('positions', e)
  }
}

function upsertIdea(idea: Idea) {
  S.ideas.set(idea.id, idea)
  graph?.upsert(idea)
}

// ---------------------------------------------------------------- boards

export async function loadBoards() {
  S.boards = (await api<{ boards: Board[] }>('GET', '/api/boards')).boards
  emit()
}

export async function openBoard(id: number) {
  S.stopFollow?.()
  const data = await api<BoardData>('GET', `/api/boards/${id}`)
  S.board = data.board
  S.ideas = new Map(data.ideas.map((i) => [i.id, i]))
  S.edges = data.edges
  S.leads = data.leads
  S.spinoffs = data.spinoffs
  S.judging = new Set(data.judging)
  S.autopilot = data.autopilot
  S.spentToday = data.spentToday
  S.selected = null
  localStorage.setItem('ideagen.board', String(id))
  graph?.setData(data.ideas, data.edges, data.judging)
  S.stopFollow = follow(id, onEvent)
  emit()
}

function onEvent(e: BoardEvent) {
  switch (e.type) {
    case 'hello':
      S.autopilot = e.autopilot
      // The server restarted and lost the settings it only keeps in memory: hand them over again.
      if (!e.settingsReceived) pushSettings(S.settings).then((p) => { S.providers = p; emit() }).catch(fail)
      break
    case 'ideas':
      for (const idea of e.ideas) {
        const before = S.ideas.get(idea.id)
        upsertIdea(idea)
        if (medal(idea) === 3 && (!before || medal(before) !== 3)) {
          graph?.burst(idea.id)
          notify(t('gold', { t: idea.title }), 'good')
        }
      }
      break
    case 'edges':
      for (const ed of e.edges) if (!S.edges.some((x) => x.from === ed.from && x.to === ed.to)) S.edges.push(ed)
      graph?.addEdges(e.edges)
      break
    case 'removed':
      for (const id of e.ids) {
        S.ideas.delete(id)
        S.edges = S.edges.filter((x) => x.from !== id && x.to !== id)
        graph?.remove(id)
        if (S.selected === id) S.selected = null
      }
      break
    case 'judging':
      for (const id of e.ids) if (e.on) S.judging.add(id); else S.judging.delete(id)
      graph?.setJudging(e.ids, e.on)
      break
    case 'board':
      S.board = e.board
      break
    case 'leads':
      S.leads = [...S.leads.filter((l) => l.source !== e.source), ...e.leads]
      break
    case 'autopilot':
      S.autopilot = e.status
      if (e.status.spentToday != null) S.spentToday = e.status.spentToday
      if (e.status.state === 'error' && e.status.code === 'no_key') notify(errorText(e.status), 'error', 8000)
      break
    case 'project':
      notify(t('projectCreated', { p: e.path }), 'good', 9000)
      notifyWorkspace(e)
      break
    case 'spinoff':
      S.spinoffs.push({ ideaId: e.ideaId, boardId: e.boardId, title: e.title })
      break
    case 'error':
      fail(e)
      break
  }
  emit()
}

// ---------------------------------------------------------------- actions

export async function seed(text: string) {
  if (!S.board) return
  const at = S.createAt ?? graph?.viewCenter() ?? { x: 0, y: 0 }
  S.createAt = null
  const clearGhost = graph?.addGhosts([at]) ?? (() => {})
  try {
    const { idea } = await api<{ idea: Idea }>('POST', `/api/boards/${S.board.id}/seed`, { text, x: at.x, y: at.y })
    upsertIdea(idea)
    emit()
  } finally {
    clearGhost()
  }
}

export async function expand(id: number) {
  if (!graph) return
  graph.setBusy([id], true)
  const clearGhosts = graph.addGhosts(graph.ghostsAround(id))
  try {
    const r = await api<{ ideas: Idea[]; edges: Edge[] }>('POST', `/api/ideas/${id}/expand`, {})
    for (const i of r.ideas) upsertIdea(i)
    graph?.addEdges(r.edges)
    emit()
  } catch (e) {
    fail(e)
  } finally {
    clearGhosts()
    graph?.setBusy([id], false)
  }
}

export async function combine(a: number, b: number) {
  if (!graph || !S.board) return
  const na = graph.nodes.get(a), nb = graph.nodes.get(b)
  graph.setBusy([a, b], true)
  const clearGhosts = na && nb ? graph.addGhosts([{ x: (na.x + nb.x) / 2, y: (na.y + nb.y) / 2 }]) : () => {}
  try {
    const r = await api<{ idea: Idea; edges: Edge[] }>('POST', `/api/boards/${S.board.id}/combine`, { a, b })
    upsertIdea(r.idea)
    graph?.addEdges(r.edges)
    emit()
  } catch (e) {
    fail(e)
  } finally {
    clearGhosts()
    graph?.setBusy([a, b], false)
  }
}

export async function seedLead(id: number, x?: number, y?: number) {
  try {
    const r = await api<{ idea: Idea }>('POST', `/api/leads/${id}/seed`, x == null ? {} : { x, y })
    upsertIdea(r.idea)
    graph?.focusOn(r.idea.id, null)
    emit()
  } catch (e) {
    fail(e)
  }
}

export async function refreshLeads(source: LeadSource) {
  if (!S.board) return
  S.refreshing[source] = Date.now()
  emit()
  const tick = setInterval(emit, 1000)
  try {
    const r = await api<{ leads: Lead[]; dropped: number; empty: boolean }>('POST', `/api/boards/${S.board.id}/leads/${source}`, {})
    S.leads = [...S.leads.filter((l) => l.source !== source), ...r.leads]
    if (r.empty) notify(t('leadsNone'))
    else if (r.dropped) notify(t('leadsDropped', { n: r.dropped }))
  } catch (e) {
    fail(e)
  } finally {
    clearInterval(tick)
    delete S.refreshing[source]
    emit()
  }
}

export async function patchBoard(patch: Record<string, unknown>) {
  if (!S.board) return
  try {
    const { board } = await api<{ board: Board }>('PATCH', `/api/boards/${S.board.id}`, patch)
    S.board = board
    S.boards = S.boards.map((b) => (b.id === board.id ? { ...b, ...board } : b))
    emit()
    return board
  } catch (e) {
    fail(e)
  }
}

export async function rejudge(id: number) {
  try {
    await api('POST', `/api/ideas/${id}/judge`, {})
  } catch (e) {
    fail(e)
  }
}

export async function setMedal(id: number, value: number | null) {
  try {
    await api('PATCH', `/api/ideas/${id}`, { medalOverride: value })
  } catch (e) {
    fail(e)
  }
}

export async function deleteIdea(idea: Idea) {
  if (!(await ask(t('confirmDelete', { t: idea.title })))) return
  try {
    await api('DELETE', `/api/ideas/${idea.id}`)
  } catch (e) {
    fail(e)
  }
}

/** Throws on failure so the button can restore itself. */
export async function makeProject(idea: Idea) {
  const r = await api<{ idea: Idea; path: string } & WorkspaceResult>('POST', `/api/ideas/${idea.id}/project`, {})
  upsertIdea(r.idea)
  notify(t('projectCreated', { p: r.path }), 'good', 9000)
  notifyWorkspace(r)
  emit()
}

function notifyWorkspace(r: WorkspaceResult) {
  if (r.workspaceError) notify(t('workspaceFailed', { e: r.workspaceError }), 'error', 9000)
  else if (r.workspaceDuplicateOf) notify(t('workspaceDuplicate', { s: r.workspaceDuplicateOf }), 'info', 9000)
  else if (r.workspace) {
    const related = r.workspaceRelated?.length ? ' ' + t('workspaceRelated', { s: r.workspaceRelated.join(', ') }) : ''
    notify(t(r.workspaceExisted ? 'workspaceExisted' : 'workspaceFiled', { p: r.workspace }) + related, 'good', 9000)
  }
}

export const reveal = (id: number) => api('POST', `/api/ideas/${id}/reveal`, {}).catch(fail)
export const copyText = (text: string) => navigator.clipboard.writeText(text).then(() => notify(t('copied')))

export async function spinoff(idea: Idea) {
  try {
    const r = await api<{ board: Board }>('POST', `/api/ideas/${idea.id}/spinoff`, {})
    await loadBoards()
    await openBoard(r.board.id)
  } catch (e) {
    fail(e)
  }
}

/** Throws on failure so the button can restore itself. */
export async function summarize() {
  if (!S.board) return
  const { board } = await api<{ board: Board }>('POST', `/api/boards/${S.board.id}/summary`, {})
  S.board = board
  emit()
}

/** One Elo duel between two of the best judged ideas; the ideas come back over SSE too. */
export async function duel() {
  if (!S.board) return
  const r = await api<{ ideas: Idea[]; a: number; scoreA: number; reasons: string[] }>('POST', `/api/boards/${S.board.id}/duel`, {})
  r.ideas.forEach(upsertIdea)
  const [a, b] = [r.ideas.find((i) => i.id === r.a), r.ideas.find((i) => i.id !== r.a)]
  const verdict = r.scoreA === 0.5 ? t('duelDraw', { a: a?.title, b: b?.title }) : t('duelWin', { w: (r.scoreA > 0.5 ? a : b)?.title, l: (r.scoreA > 0.5 ? b : a)?.title })
  notify([verdict, r.reasons[0]].filter(Boolean).join(' '), 'good', 7000)
  emit()
}

export function focusIdea(id: number, k: number | null) {
  selectIdea(id)
  graph?.focusOn(id, k)
}

export function onOpenIdea(boardId: number, ideaId: number) {
  const go = boardId === S.board?.id ? Promise.resolve() : openBoard(boardId)
  go.then(() => focusIdea(ideaId, 1)).catch(fail)
}

export async function exportBoard() {
  if (!S.board) return
  try {
    const md = await api<string>('GET', `/api/boards/${S.board.id}/export.md?lang=${getLang()}`)
    download(md, `${S.board.title.replace(/[^\p{L}\p{N}]+/gu, '-').toLowerCase()}.md`)
  } catch (e) {
    fail(e)
  }
}

export function warnMissingKeys() {
  const status = new Map((S.providers?.providers ?? []).map((p) => [p.id, p]))
  for (const p of providersInUse(S.settings)) {
    const st = status.get(p)
    if (st && !st.local && st.needsKey && !st.keySource) notify(t('needKey', { p: st.label }), 'error', 10000)
  }
}

// ---------------------------------------------------------------- exhibition

export async function enterExhibit() {
  S.exhibit = true
  S.selected = null
  if (graph) {
    graph.followNewest = true
    graph.insets = { top: 250, right: 40, bottom: 130, left: 40 }
    graph.select(null)
  }
  emit()
  try {
    await document.documentElement.requestFullscreen?.()
  } catch {
    /* fullscreen is optional */
  }
  setTimeout(() => graph?.fit(), 300)
  notify(t('exhibitExit'))
}

export function leaveExhibit() {
  if (!S.exhibit) return
  S.exhibit = false
  if (graph) {
    graph.followNewest = false
    graph.insets = { top: 30, right: 30, bottom: 100, left: 30 }
  }
  if (document.fullscreenElement) document.exitFullscreen?.()
  emit()
}

// ---------------------------------------------------------------- start

function onKey(e: KeyboardEvent) {
  const typing = /INPUT|TEXTAREA|SELECT/.test(document.activeElement?.tagName ?? '')
  if (e.key === 'Escape') {
    if (S.view) { S.view = null; emit() }
    else if (graph?.pick) graph.setPick(null)
    else if (S.exhibit) leaveExhibit()
    else if (!S.dialog && !S.confirm) selectIdea(null)
  }
  if (typing || S.dialog) return
  if (e.key === '/') {
    e.preventDefault()
    seedInput?.focus()
  }
  if (e.key === 'f') graph?.fit()
}

let started = false
export async function main() {
  if (started) return // StrictMode runs effects twice
  started = true
  document.addEventListener('keydown', onKey)
  document.addEventListener('fullscreenchange', () => {
    if (!document.fullscreenElement) leaveExhibit()
  })
  setInterval(() => S.autopilot?.until && emit(), 1000)
  // ?debug exposes the canvas for scripted demos and tests (node positions, camera).
  if (new URLSearchParams(location.search).has('debug')) (window as unknown as Record<string, unknown>).ideagen = { graph: () => graph, state: () => S }
  try {
    S.providers = await pushSettings(S.settings)
    warnMissingKeys()
  } catch (e) {
    fail(e)
  }
  await loadBoards()
  const last = Number(localStorage.getItem('ideagen.board'))
  const pick = S.boards.find((b) => b.id === last) ?? S.boards[0]
  if (pick) await openBoard(pick.id)
  else openDialog({ kind: 'newBoard', first: true })
}

/** The autopilot status line (top bar chip and exhibition overlay). */
export function autopilotChip() {
  const st = S.autopilot ?? { state: 'manual' }
  if (S.board?.config.mode !== 'auto') return { text: '', title: '', tone: '' }
  const secs = st.until ? Math.max(0, Math.ceil((st.until - Date.now()) / 1000)) : 0
  const time = st.until ? new Date(st.until).toLocaleTimeString(getLang() === 'sv' ? 'sv-SE' : 'en-GB', { hour: '2-digit', minute: '2-digit' }) : ''
  const key = st.state === 'idle' ? (st.last === 'empty' ? 'ap_idle_empty' : st.last === 'nothing_to_do' ? 'ap_idle_nothing' : 'ap_idle') : `ap_${st.state}`
  return {
    text: t(key, { s: `${secs} s`, time, m: st.message ?? '' }),
    title: t('apMeta', { n: st.stepsLastHour ?? 0, usd: (st.spentToday ?? S.spentToday ?? 0).toFixed(3) }),
    tone: ['error', 'backoff', 'board_budget', 'global_budget', 'hourly'].includes(st.state) ? 'warn' : st.state === 'running' ? 'busy' : '',
  }
}

export const secondsSince = (t0: number) => Math.round((Date.now() - t0) / 1000)
