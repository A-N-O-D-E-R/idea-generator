// Trends across all boards, by keyword. The table is the primary form (it is
// also the accessible view); each row carries a one-series sparkline of ideas
// per day with a hover crosshair, a signed change against the previous period,
// and a meter for the mean jury score (3–15).

import { useEffect, useState } from 'react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { api } from '@/api'
import { errorText, locale, t } from '@/i18n'
import type { Dashboard as DashboardData, KeywordIdea } from '@/types'
import { Grow, RankItem, Seg } from './Field'
import { S, emit, onOpenIdea } from '@/store'

const fmt = (n: number) => new Intl.NumberFormat(locale(), { notation: 'compact' }).format(n)

export function Tile({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="rounded-lg border bg-card px-3.5 py-3">
      <div className="text-xs text-subtle">{label}</div>
      <div className="text-[28px] font-semibold tabular-nums">{value}</div>
      {sub ? <div className="text-xs text-muted-foreground">{sub}</div> : null}
    </div>
  )
}

export function ViewHead({ title, days, setDays, children }: {
  title: string; days: number; setDays: (n: number) => void; children?: React.ReactNode
}) {
  return (
    <header className="flex items-center gap-4">
      <h2 className="m-0 flex-1 text-2xl font-bold">{title}</h2>
      <div className="flex flex-wrap items-center gap-1" role="group">
        {[7, 30, 90].map((n) => <Seg key={n} on={days === n} onClick={() => setDays(n)}>{t('days', { n })}</Seg>)}
      </div>
      {children}
      <Button variant="ghost" size="icon" aria-label={t('close')} title={t('close')} onClick={() => { S.view = null; emit() }}>✕</Button>
    </header>
  )
}

type Tip = { x: number; y: number; n: number; day: string } | null

/** One series: a 2px line, the latest day marked, hover snaps to the day. */
function Sparkline({ series, days, keyword, onTip }: { series: number[]; days: string[]; keyword: string; onTip: (t: Tip) => void }) {
  const [at, setAt] = useState<number | null>(null)
  const W = 132, H = 30, pad = 3
  const max = Math.max(1, ...series)
  const x = (i: number) => pad + (i * (W - pad * 2)) / Math.max(1, series.length - 1)
  const y = (v: number) => H - pad - (v / max) * (H - pad * 2)
  const pts = series.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ')
  const last = series.length - 1
  return (
    <svg
      width={W}
      height={H}
      viewBox={`0 0 ${W} ${H}`}
      className="block overflow-visible"
      role="img"
      aria-label={`${keyword}: ${series.reduce((a, b) => a + b, 0)} / ${days.length}`}
      onPointerMove={(e) => {
        const r = e.currentTarget.getBoundingClientRect()
        const i = Math.max(0, Math.min(last, Math.round(((e.clientX - r.left - pad) / (W - pad * 2)) * last)))
        setAt(i)
        onTip({ x: e.clientX + 12, y: e.clientY - 30, n: series[i], day: days[i] })
      }}
      onPointerLeave={() => { setAt(null); onTip(null) }}
    >
      <line x1={pad} x2={W - pad} y1={H - pad} y2={H - pad} className="stroke-border" strokeWidth={1} />
      <polyline points={pts} fill="none" className="stroke-primary" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
      <circle cx={x(last)} cy={y(series[last])} r={2.5} className="fill-primary" />
      {at != null ? (
        <>
          <line x1={x(at)} x2={x(at)} y1={0} y2={H} className="stroke-muted-foreground" strokeWidth={1} />
          <circle cx={x(at)} cy={y(series[at])} r={3.5} className="fill-primary stroke-card" strokeWidth={2} />
        </>
      ) : null}
    </svg>
  )
}

function Change({ recent, previous }: { recent: number; previous: number }) {
  const d = recent - previous
  const arrow = d > 0 ? '▲' : d < 0 ? '▼' : '–'
  return <span className={cn('whitespace-nowrap text-subtle', d > 0 && 'text-foreground')}>{`${arrow} ${d > 0 ? '+' : ''}${d}`}</span>
}

function Meter({ mean }: { mean: number | null }) {
  if (mean == null) return <span className="text-muted-foreground">–</span>
  const pct = Math.max(0, Math.min(1, (mean - 3) / 12)) * 100
  return (
    <span className="inline-flex items-center gap-2">
      <span className="inline-block h-2 w-20 overflow-hidden rounded bg-track" role="img" aria-label={`${mean} / 15`}>
        <span className="block h-full rounded bg-primary" style={{ width: `${pct}%` }} />
      </span>
      <span className="text-xs text-subtle tabular-nums">{mean.toFixed(1)}</span>
    </span>
  )
}

export function Dashboard({ keyword: initial }: { keyword: string | null }) {
  const [days, setDays] = useState(30)
  const [data, setData] = useState<DashboardData | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [keyword, setKeyword] = useState(initial)
  const [drill, setDrill] = useState<{ ideas: KeywordIdea[] } | { error: string } | null>(null)
  const [tip, setTip] = useState<Tip>(null)

  useEffect(() => {
    let live = true
    api<DashboardData>('GET', `/api/dashboard?days=${days}`)
      .then((d) => live && (setData(d), setError(null)))
      .catch((e) => live && setError(errorText(e)))
    return () => { live = false }
  }, [days])

  useEffect(() => {
    if (!keyword) return
    let live = true
    api<{ ideas: KeywordIdea[] }>('GET', `/api/keywords/${encodeURIComponent(keyword)}/ideas`)
      .then((r) => live && setDrill(r))
      .catch((e) => live && setDrill({ error: errorText(e) }))
    return () => { live = false }
  }, [keyword])

  const close = () => { S.view = null; emit() }
  return (
    <section tabIndex={-1} className="fixed inset-0 z-30 overflow-auto bg-background px-6 pt-4.5 pb-10">
      <ViewHead title={t('dashTitle')} days={days} setDays={setDays} />
      {error ? <p className="text-destructive">{error}</p> : !data ? null : (
        <>
          <div className="my-4.5 grid grid-cols-[repeat(4,minmax(140px,1fr))] gap-3">
            <Tile label={t('statIdeas')} value={fmt(data.totals.ideas)} />
            <Tile label={t('statBoards')} value={fmt(data.totals.boards)} />
            <Tile label={t('statKeywords')} value={fmt(data.totals.keywords)} />
            <Tile label={t('statRecent', { n: days })} value={fmt(data.totals.recent)} />
          </div>
          {!data.keywords.length ? (
            <p className="text-muted-foreground">{t('dashEmpty')}</p>
          ) : (
            <div className="grid grid-cols-[minmax(0,1fr)_360px] gap-4.5 max-[1100px]:grid-cols-1">
              <div>
                <div className="overflow-hidden rounded-lg border bg-card">
                  <Table>
                    <TableHeader>
                      <TableRow className="hover:bg-transparent">
                        {[['keyword', ''], ['trend', ''], ['count', 'text-right'], ['change', 'text-right'], ['onBoards', 'text-right'], ['meanJury', '']].map(([k, c]) => (
                          <TableHead key={k} className={cn('text-xs font-medium text-muted-foreground', c)}>{t(k)}</TableHead>
                        ))}
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {data.keywords.map((k) => (
                        <TableRow
                          key={k.keyword}
                          tabIndex={0}
                          className={cn('cursor-pointer border-border hover:bg-muted', keyword === k.keyword && 'bg-muted')}
                          onClick={() => setKeyword(k.keyword)}
                          onKeyDown={(e) => e.key === 'Enter' && setKeyword(k.keyword)}
                        >
                          <TableCell><button type="button" className="cursor-pointer text-primary hover:underline">{k.keyword}</button></TableCell>
                          <TableCell><Sparkline series={k.series} days={data.days} keyword={k.keyword} onTip={setTip} /></TableCell>
                          <TableCell className="text-right tabular-nums">{k.count}</TableCell>
                          <TableCell className="text-right tabular-nums"><Change recent={k.recent} previous={k.previous} /></TableCell>
                          <TableCell className="text-right tabular-nums">{k.boards}</TableCell>
                          <TableCell><Meter mean={k.meanSum} /></TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
                <small className="text-muted-foreground">{t('tableNote')}</small>
              </div>
              <aside>
                {keyword && drill ? (
                  <section className="mb-3 rounded-lg border bg-card px-3.5 py-2.5">
                    {'error' in drill ? <p className="text-destructive">{drill.error}</p> : (
                      <>
                        <h3 className="mt-1 mb-2 text-sm font-semibold">{t('ideasWith', { k: keyword })}</h3>
                        <ul className="m-0 list-none p-0">
                          {drill.ideas.map((i) => {
                            const sum = i.originality != null ? i.originality + i.feasibility! + i.impact! : null
                            return (
                              <li key={i.id} className="min-w-0">
                                <RankItem onClick={() => { close(); onOpenIdea(i.boardId, i.id) }}>
                                  <Grow>{i.title}</Grow>
                                  <span className="text-xs text-muted-foreground">{i.boardTitle}</span>
                                  <span className="whitespace-nowrap text-subtle tabular-nums">{sum ?? ''}</span>
                                </RankItem>
                              </li>
                            )
                          })}
                        </ul>
                      </>
                    )}
                  </section>
                ) : null}
                <section className="mb-3 rounded-lg border bg-card px-3.5 py-2.5">
                  <h3 className="mt-1 mb-2 text-sm font-semibold">{t('boardKeywords')}</h3>
                  {data.boards.map((b) => (
                    <div key={b.id} className="my-2">
                      <strong>{b.title}</strong>
                      <div className="my-2 flex flex-wrap gap-1.5">
                        {b.keywords.map((k) => (
                          <button
                            key={k.keyword}
                            type="button"
                            className="cursor-pointer rounded-full border px-2.5 py-0.5 text-xs text-subtle hover:border-primary hover:text-foreground"
                            onClick={() => setKeyword(k.keyword)}
                          >
                            {`${k.keyword} · ${k.count}`}
                          </button>
                        ))}
                      </div>
                    </div>
                  ))}
                </section>
              </aside>
            </div>
          )}
        </>
      )}
      {tip ? (
        <div
          role="tooltip"
          className="pointer-events-none fixed z-50 rounded-lg border border-border bg-tip px-2.5 py-1.5 text-xs shadow-[0_8px_24px_rgba(59,13,42,.18)]"
          style={{ left: tip.x, top: tip.y }}
        >
          <strong>{tip.n}</strong>{` ${t('count').toLowerCase()} · ${tip.day}`}
        </div>
      ) : null}
    </section>
  )
}
