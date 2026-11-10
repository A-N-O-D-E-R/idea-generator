// The report: insights, ideas and Claude Code projects across all boards,
// for a chosen period. Conclusions are written on request (one LLM call) and
// saved, so opening the report costs nothing.

import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { api, download } from '@/api'
import { errorText, getLang, locale, t } from '@/i18n'
import type { BriefIdea, Report as ReportData } from '@/types'
import { Tile, ViewHead } from './Dashboard'
import { Grow, RankItem } from './Field'
import { S, copyText, downloadProject, emit, fail, isShared, onOpenIdea, openBoard, reveal } from '@/store'

const MEDAL_ICON = ['', '🥉', '🥈', '🥇']
const startCmd = (path: string) => `cd "${path}" && claude`
const close = () => { S.view = null; emit() }

const List = ({ items }: { items: string[] }) => (
  <ul className="my-0.5 list-disc pl-4.5">{items.map((x, i) => <li key={i}>{x}</li>)}</ul>
)
const H3 = ({ children }: { children: React.ReactNode }) => <h3 className="mt-1 mb-2 text-[15px] font-semibold">{children}</h3>
const H4 = ({ children }: { children: React.ReactNode }) => <h4 className="mt-2.5 mb-1 text-[13px] font-semibold text-subtle">{children}</h4>
const block = 'mb-3.5 rounded-lg border bg-card px-4 py-3'
const linkish = 'cursor-pointer text-left text-primary hover:underline'

function IdeaLine({ i }: { i: BriefIdea }) {
  return (
    <RankItem onClick={() => { close(); onOpenIdea(i.boardId, i.id) }}>
      <Grow>{i.title}</Grow>
      <span className="whitespace-nowrap text-subtle tabular-nums">{`${i.sum ?? ''} ${i.medal ? MEDAL_ICON[i.medal] : ''}`}</span>
    </RankItem>
  )
}

export function Report() {
  const [days, setDays] = useState(30)
  const [r, setR] = useState<ReportData | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [thinking, setThinking] = useState(false)

  useEffect(() => {
    let live = true
    api<ReportData>('GET', `/api/report?days=${days}`)
      .then((d) => live && (setR(d), setError(null)))
      .catch((e) => live && setError(errorText(e)))
    return () => { live = false }
  }, [days])

  async function synthesize() {
    setThinking(true)
    try {
      setR(await api<ReportData>('POST', '/api/report/synthesis', { days, lang: getLang() }))
    } catch (e) {
      fail(e)
    } finally {
      setThinking(false)
    }
  }

  async function exportReport() {
    try {
      const md = await api<string>('GET', `/api/report.md?days=${days}&lang=${getLang()}`)
      download(md, `rapport-${new Date().toISOString().slice(0, 10)}.md`)
    } catch (e) {
      fail(e)
    }
  }

  const s = r?.synthesis
  return (
    <section tabIndex={-1} className="fixed inset-0 z-30 overflow-auto bg-background px-6 pt-4.5 pb-10">
      <ViewHead title={t('report')} days={days} setDays={setDays}>
        <Button variant="secondary" size="sm" onClick={exportReport}>{t('export')}</Button>
      </ViewHead>
      {error ? <p className="text-destructive">{error}</p> : !r ? null : (
        <>
          <div className="my-4.5 grid grid-cols-[repeat(4,minmax(140px,1fr))] gap-3">
            <Tile label={t('statIdeas')} value={`${r.totals.ideas}`} sub={t('reportNew', { n: r.totals.newIdeas })} />
            <Tile label={t('reportMedals')} value={`🥇 ${r.totals.medals.gold}  🥈 ${r.totals.medals.silver}  🥉 ${r.totals.medals.bronze}`} />
            <Tile label={t('reportProjects')} value={`${r.totals.projects}`} />
            <Tile label={t('reportSpent', { n: days })} value={`$${r.totals.spentUsd.toFixed(2)}`} />
          </div>

          <section className={block}>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <H3>{t('conclusions')}</H3>
              <Button size="sm" disabled={thinking} onClick={synthesize}>
                {thinking ? t('thinking') : s ? t('rewriteConclusions') : t('writeConclusions')}
              </Button>
            </div>
            {s ? (
              <>
                <p className="text-xs text-muted-foreground">
                  {t('conclusionsFrom', { date: new Date(s.createdAt.replace(' ', 'T') + 'Z').toLocaleString(locale()), n: s.days })}
                </p>
                <p className="text-[15px] font-semibold">{s.headline}</p>
                <div className="grid grid-cols-2 gap-x-3">
                  <div><H4>{t('insights')}</H4><List items={s.insights} /></div>
                  <div>
                    <H4>{t('standouts')}</H4>
                    <ul className="my-0.5 list-disc pl-4.5">
                      {s.standouts.map((x, i) => (
                        <li key={i}><strong>{x.title}</strong><span className="text-muted-foreground">{` · ${x.board}`}</span>{` – ${x.why}`}</li>
                      ))}
                    </ul>
                  </div>
                  <div><H4>{t('opportunities')}</H4><List items={s.opportunities} /></div>
                  <div><H4>{t('nextSteps')}</H4><List items={s.nextSteps} /></div>
                </div>
              </>
            ) : <p className="text-muted-foreground">{t('conclusionsHint')}</p>}
          </section>

          <section className={block}>
            <H3>{t('reportProjects')}</H3>
            {!r.projects.length ? <p className="text-muted-foreground">{t('noProjects')}</p> : (
              <ul className="grid list-none gap-2.5 p-0">
                {r.projects.map((p) => (
                  <li key={p.id} className="border-t pt-2">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <button type="button" className={linkish} onClick={() => { close(); onOpenIdea(p.boardId, p.id) }}>
                        <strong>{p.title}</strong>
                      </button>
                      <span className="text-xs text-muted-foreground">
                        {`${p.boardTitle}${p.sum != null ? ` · ${p.sum}/15 ${MEDAL_ICON[p.medal ?? 0] ?? ''}` : ''}`}
                      </span>
                    </div>
                    {isShared() ? (
                      <div className="mt-1.5">
                        <Button variant="secondary" size="sm" onClick={() => downloadProject(p.id)}>{`📦 ${t('downloadProject')}`}</Button>
                      </div>
                    ) : (
                      <>
                        <code className="my-1.5 block overflow-x-auto rounded-md bg-background px-2 py-1.5 font-mono text-xs whitespace-nowrap text-subtle">
                          {startCmd(p.path)}
                        </code>
                        <div className="flex flex-wrap items-center gap-2">
                          <Button variant="secondary" size="sm" onClick={() => copyText(startCmd(p.path))}>{t('copyStart')}</Button>
                          <Button variant="secondary" size="sm" onClick={() => reveal(p.id)}>{t('revealProject')}</Button>
                        </div>
                      </>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="grid grid-cols-[repeat(auto-fill,minmax(360px,1fr))] gap-3.5">
            {r.boards.map((b) => (
              <article key={b.id} className="rounded-lg border bg-card px-4 py-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <button type="button" className={linkish} onClick={() => { close(); openBoard(b.id).catch(fail) }}>
                    <span className="mt-1 mb-2 block text-[15px] font-semibold">{b.title}</span>
                  </button>
                  <span className="text-xs text-muted-foreground">
                    {`${b.ideaCount} ${t('ideasWord')} · ${t('reportNew', { n: b.newIdeas })}${b.mode === 'auto' ? ` · ${t('auto')}` : ''}`}
                  </span>
                </div>
                {b.goal ? <p className="text-muted-foreground">{b.goal}</p> : null}
                {b.summary ? (
                  <div>
                    <p className="my-1">
                      {b.summary.headline}
                      {b.summary.stale ? <span className="text-xs text-gold">{` (${t('summaryOld')})`}</span> : null}
                    </p>
                    {b.summary.gaps?.length ? <><H4>{t('gaps')}</H4><List items={b.summary.gaps} /></> : null}
                    {b.summary.nextSteps?.length ? <><H4>{t('nextSteps')}</H4><List items={b.summary.nextSteps} /></> : null}
                  </div>
                ) : <p className="text-xs text-muted-foreground">{t('noBoardSummary')}</p>}
                {b.best.length ? <><H4>{t('topIdeas')}</H4><div>{b.best.map((i) => <IdeaLine key={i.id} i={i} />)}</div></> : null}
                {b.projects.length ? <p className="text-xs">{`📁 ${b.projects.map((p) => p.title).join(' · ')}`}</p> : null}
              </article>
            ))}
          </section>
        </>
      )}
    </section>
  )
}
