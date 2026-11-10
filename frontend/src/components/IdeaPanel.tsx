// The right panel: the selected idea, or the board overview (evolution, summary, top list).

import { useState } from 'react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { evolutionStats } from '@/lib/evolve'
import { CRITERIA, fitness, medal, scoreSum } from '@/lib/score'
import { safeHref } from '@/api'
import { t } from '@/i18n'
import type { Idea } from '@/types'
import { Grow, RankItem, Seg, Swatch } from './Field'
import {
  MEDAL_ICON, S, copyText, deleteIdea, emit, expand, fail, focusIdea, graph, ideaList, makeProject, notify, openBoard,
  openDialog, rejudge, reveal, selectIdea, setMedal, spinoff, summarize, duel, useStore,
} from '@/store'

const H3 = ({ children }: { children: React.ReactNode }) => (
  <h3 className="mt-4.5 mb-2 text-[13px] font-semibold tracking-[.06em] text-muted-foreground uppercase">{children}</h3>
)
const H4 = ({ children }: { children: React.ReactNode }) => (
  <h4 className="mt-3 mb-1 text-[13px] font-semibold text-subtle">{children}</h4>
)
const linkish = 'cursor-pointer text-left text-primary hover:underline'

function Spinner() {
  return <span className="size-3.5 animate-spin rounded-full border-2 border-gold/30 border-t-gold" />
}

function ScoreBlock({ idea }: { idea: Idea }) {
  const { judging } = useStore()
  if (judging.has(idea.id)) {
    return <div className="my-3 flex items-center gap-2.5 text-gold"><Spinner />{t('judging')}</div>
  }
  const sum = scoreSum(idea)
  const m = medal(idea)
  if (sum == null && m == null) {
    return (
      <div className="flex flex-wrap items-center gap-2 text-muted-foreground">
        {t('notJudged')}
        <Button variant="secondary" size="sm" onClick={() => rejudge(idea.id)}>{t('rejudge')}</Button>
      </div>
    )
  }
  return (
    <div className="my-3 rounded-lg border bg-muted p-3">
      <div className="mb-2 flex items-baseline gap-2.5">
        {sum != null ? (
          <span className="text-[28px] font-bold">{sum}<small className="text-sm font-medium text-muted-foreground">/15</small></span>
        ) : null}
        {m ? <span className="text-[22px]">{MEDAL_ICON[m]}</span> : null}
        {idea.medalOverride != null ? (
          <span className="text-muted-foreground">{`(${t('medal')}: ${MEDAL_ICON[idea.medalOverride]})`}</span>
        ) : null}
      </div>
      {sum != null
        ? CRITERIA.map((c) => (
            <div key={c} className="my-1 grid grid-cols-[110px_1fr_16px] items-center gap-2 text-xs">
              <span className="text-subtle">{t(c)}</span>
              <span className="grid grid-cols-5 gap-0.5" role="img" aria-label={`${t(c)} ${idea[c]}/5`}>
                {[1, 2, 3, 4, 5].map((n) => (
                  <span key={n} className={cn('h-2 rounded bg-track', n <= idea[c]! && 'bg-primary')} />
                ))}
              </span>
              <span className="text-right tabular-nums">{idea[c]}</span>
            </div>
          ))
        : null}
      {idea.reason ? <p className="mt-2.5 text-subtle italic">{idea.reason}</p> : null}
      {idea.elo != null ? <p className="mt-1 text-xs text-muted-foreground tabular-nums">{t('eloLine', { e: Math.round(idea.elo), n: idea.duels })}</p> : null}
    </div>
  )
}

function IdeaView({ idea }: { idea: Idea }) {
  const { spinoffs } = useStore()
  const [making, setMaking] = useState(false)
  const spun = spinoffs.filter((s) => s.ideaId === idea.id)
  const startCmd = idea.projectPath ? `cd "${idea.projectPath}" && claude` : ''
  const medalBtn = (value: number | null, label: string) => (
    <Seg key={String(value)} on={idea.medalOverride === value} onClick={() => setMedal(idea.id, value)}>{label}</Seg>
  )
  const act = 'justify-start'
  return (
    <>
      <div className="mb-1.5 flex items-center gap-2.5">
        <Button variant="ghost" size="icon" title={t('close')} aria-label={t('close')} onClick={() => selectIdea(null)}>✕</Button>
        <span className="inline-flex items-center gap-1.5 text-xs text-subtle"><Swatch kind={idea.kind} />{t(`kind_${idea.kind}`)}</span>
        <span className="text-muted-foreground">{t('generation', { n: idea.generation })}</span>
      </div>
      {idea.kind === 'guest' && idea.originBoardId ? (
        <button type="button" className={linkish} onClick={() => openBoard(idea.originBoardId!).catch(fail)}>
          {`↗ ${t('fromBoard', { b: idea.originBoardTitle ?? '?' })}`}
        </button>
      ) : null}
      <h2 className="my-1.5 text-xl leading-tight font-bold">{idea.title}</h2>
      {idea.pitch ? <p className="mb-2 text-subtle">{idea.pitch}</p> : null}
      {safeHref(idea.sourceUrl) ? (
        <a className="text-xs" href={safeHref(idea.sourceUrl)} target="_blank" rel="noopener noreferrer">{`${t('source')} ↗`}</a>
      ) : null}
      <ScoreBlock idea={idea} />
      {idea.keywords?.length ? (
        <div className="my-2 flex flex-wrap gap-1.5">
          {idea.keywords.map((k) => (
            <button
              key={k}
              type="button"
              className="cursor-pointer rounded-full border px-2.5 py-0.5 text-xs text-subtle hover:border-primary hover:text-foreground"
              onClick={() => { S.view = { kind: 'dashboard', keyword: k }; emit() }}
            >
              {k}
            </button>
          ))}
        </div>
      ) : null}
      <div className="my-2.5 flex flex-wrap items-center gap-1" role="group" aria-label={t('medal')}>
        <span className="text-muted-foreground">{t('medal')}</span>
        {medalBtn(null, t('medalAuto'))}{medalBtn(0, '–')}{medalBtn(1, '🥉')}{medalBtn(2, '🥈')}{medalBtn(3, '🥇')}
      </div>
      <div className="my-3 grid grid-cols-2 gap-1.5">
        <Button className={act} title={t('expandHint')} onClick={() => expand(idea.id)}>{`＋ ${t('expand')}`}</Button>
        <Button variant="secondary" className={act} onClick={() => { graph?.setPick(idea.id); notify(t('combinePick')) }}>
          {`⚡ ${t('combineWith')}`}
        </Button>
        <Button variant="secondary" className={act} onClick={() => rejudge(idea.id)}>{t('rejudge')}</Button>
        <Button variant="secondary" className={act} title={t('announceHint')} onClick={() => openDialog({ kind: 'announce', idea })}>
          {`📣 ${t('announce')}`}
        </Button>
        <Button variant="secondary" className={act} title={t('spinoffHint')} onClick={() => spinoff(idea)}>{`⤴ ${t('spinoff')}`}</Button>
      </div>
      <div className="my-2.5">
        {idea.projectPath ? (
          <>
            <div className="mb-1.5 text-xs break-all text-subtle" title={idea.projectPath}>{`📁 ${idea.projectPath}`}</div>
            <div className="flex flex-wrap items-center gap-2">
              <Button variant="secondary" size="sm" onClick={() => reveal(idea.id)}>{t('revealProject')}</Button>
              <Button variant="secondary" size="sm" onClick={() => copyText(startCmd)}>{t('copyStart')}</Button>
            </div>
          </>
        ) : (
          <Button
            variant="outline"
            title={t('projectHint')}
            disabled={making}
            className="w-full border-[#4a4637] bg-[#24221b] font-semibold text-[#f1e7c8] hover:bg-[#2e2b21] dark:border-[#4a4637] dark:bg-[#24221b] dark:hover:bg-[#2e2b21]"
            onClick={() => {
              setMaking(true)
              makeProject(idea).catch(fail).finally(() => setMaking(false))
            }}
          >
            {making ? t('projectMaking') : `→ ${t('project')}`}
          </Button>
        )}
      </div>
      {spun.length ? (
        <div className="my-2.5 grid gap-1">
          <span className="text-muted-foreground">{t('spinoffs')}</span>
          {spun.map((s) => (
            <button key={s.boardId} type="button" className={linkish} onClick={() => openBoard(s.boardId).catch(fail)}>{`⤴ ${s.title}`}</button>
          ))}
        </div>
      ) : null}
      <Button variant="outline" size="sm" className="border-[#4a2a2e] bg-transparent text-destructive dark:border-[#4a2a2e] dark:bg-transparent" onClick={() => deleteIdea(idea)}>
        {t('deleteIdea')}
      </Button>
    </>
  )
}

function Overview() {
  const { board } = useStore()
  const [thinking, setThinking] = useState(false)
  const [duelling, setDuelling] = useState(false)
  if (!board) return null
  const ideas = ideaList()
  const stats = evolutionStats(ideas)
  const ranked = ideas.filter((i) => fitness(i) != null).sort((x, y) => fitness(y)! - fitness(x)!).slice(0, 12)
  const byElo = ideas.filter((i) => i.elo != null).sort((x, y) => y.elo! - x.elo!).slice(0, 8)
  const fmt = (v: number | null) => (v == null ? '–' : v.toFixed(1))
  const s = board.summary
  return (
    <>
      <div className="mb-1.5 flex items-center gap-2.5"><H3>{t('overview')}</H3></div>
      {!ideas.length ? <p className="text-muted-foreground">{t('noIdeasYet')}</p> : null}
      {stats.count ? (
        <div className="my-2 rounded-lg bg-muted px-3 py-2.5">
          <span className="text-muted-foreground">{t('evolution')}</span>
          <div>
            {stats.latest != null
              ? t('evoLine', { g: stats.generation, first: fmt(stats.first), latest: fmt(stats.latest), best: stats.best })
              : t('evoShort', { g: stats.generation, best: stats.best })}
          </div>
        </div>
      ) : null}
      <section className="mt-2">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <H3>{t('summary')}</H3>
          {ideas.length >= 2 ? (
            <Button
              variant="secondary"
              size="sm"
              disabled={thinking}
              onClick={() => {
                setThinking(true)
                summarize().catch(fail).finally(() => setThinking(false))
              }}
            >
              {thinking ? t('thinking') : s ? t('resummarize') : t('summarize')}
            </Button>
          ) : null}
        </div>
        {s ? (
          <>
            {s.ideaCount !== ideas.length ? <p className="text-xs text-gold">{t('summaryStale', { n: ideas.length - s.ideaCount })}</p> : null}
            <p className="font-semibold">{s.headline}</p>
            {s.themes.length ? <H4>{t('themes')}</H4> : null}
            {s.themes.map((th, i) => <p key={i}><strong>{`${th.name}. `}</strong>{th.insight}</p>)}
            {s.strongest.length ? <H4>{t('strongest')}</H4> : null}
            <ul className="my-1 list-disc pl-4.5">{s.strongest.map((x, i) => <li key={i}><strong>{x.title}</strong>{` – ${x.why}`}</li>)}</ul>
            {s.gaps.length ? <H4>{t('gaps')}</H4> : null}
            <ul className="my-1 list-disc pl-4.5">{s.gaps.map((x, i) => <li key={i}>{x}</li>)}</ul>
            {s.nextSteps.length ? <H4>{t('nextSteps')}</H4> : null}
            <ul className="my-1 list-disc pl-4.5">{s.nextSteps.map((x, i) => <li key={i}>{x}</li>)}</ul>
          </>
        ) : null}
      </section>
      {ranked.length >= 2 ? (
        <section className="mt-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <H3>{t('eloTitle')}</H3>
            <Button
              variant="secondary"
              size="sm"
              disabled={duelling}
              title={t('duelHint')}
              onClick={() => {
                setDuelling(true)
                duel().catch(fail).finally(() => setDuelling(false))
              }}
            >
              {duelling ? t('thinking') : t('duel')}
            </Button>
          </div>
          {byElo.length ? (
            <ol className="m-0 grid list-none grid-cols-[minmax(0,1fr)] gap-0.5 p-0">
              {byElo.map((i) => (
                <li key={i.id} className="min-w-0">
                  <RankItem onClick={() => focusIdea(i.id, Math.max(graph?.cam.k ?? 1, 0.9))}>
                    <Swatch kind={i.kind} />
                    <Grow>{i.title}</Grow>
                    <span className="whitespace-nowrap text-subtle tabular-nums">{`${Math.round(i.elo!)} · ${i.duels}`}</span>
                  </RankItem>
                </li>
              ))}
            </ol>
          ) : <p className="text-xs text-muted-foreground">{t('duelHint')}</p>}
        </section>
      ) : null}
      {ranked.length ? <H3>{t('topIdeas')}</H3> : null}
      <ol className="m-0 grid list-none grid-cols-[minmax(0,1fr)] gap-0.5 p-0">
        {ranked.map((i) => (
          <li key={i.id} className="min-w-0">
            <RankItem onClick={() => focusIdea(i.id, Math.max(graph?.cam.k ?? 1, 0.9))}>
              <Swatch kind={i.kind} />
              <Grow>{i.title}</Grow>
              <span className="whitespace-nowrap text-subtle tabular-nums">{`${scoreSum(i) ?? ''} ${medal(i) ? MEDAL_ICON[medal(i)!] : ''}`}</span>
            </RankItem>
          </li>
        ))}
      </ol>
    </>
  )
}

export function IdeaPanel() {
  const { selected, ideas } = useStore()
  const idea = selected != null ? ideas.get(selected) : undefined
  return (
    <aside aria-live="polite" className="w-[360px] flex-none overflow-auto border-l bg-card px-4 pt-3.5 pb-6 max-[1100px]:w-[300px]">
      {idea ? <IdeaView key={idea.id} idea={idea} /> : <Overview />}
    </aside>
  )
}
