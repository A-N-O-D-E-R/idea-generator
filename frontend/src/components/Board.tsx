// The stage: the canvas (lib/graph.ts), zoom buttons, the seed bar, the hover
// card and the exhibition overlay.

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Graph } from '@/lib/graph'
import { fitness, medal, scoreSum } from '@/lib/score'
import { t } from '@/i18n'
import {
  MEDAL_ICON, S, autopilotChip, fail, graph, graphHandlers, ideaList, seed, setGraph, setSeedInput, useStore,
} from '@/store'

const pointer = { x: 0, y: 0 }

function HoverCard() {
  const { hover, ideas, exhibit } = useStore()
  const ref = useRef<HTMLDivElement>(null)
  const idea = hover != null && !exhibit ? ideas.get(hover) : undefined
  const place = () => {
    const card = ref.current
    if (!card) return
    card.style.left = `${Math.min(pointer.x + 16, innerWidth - card.offsetWidth - 12)}px`
    card.style.top = `${Math.min(pointer.y + 16, innerHeight - card.offsetHeight - 12)}px`
  }
  useLayoutEffect(place)
  useEffect(() => {
    const move = (e: PointerEvent) => {
      pointer.x = e.clientX
      pointer.y = e.clientY
      place()
    }
    addEventListener('pointermove', move)
    return () => removeEventListener('pointermove', move)
  }, [])
  const sum = idea ? scoreSum(idea) : null
  const m = idea ? medal(idea) : null
  return (
    <div
      ref={ref}
      role="tooltip"
      className={cn(
        'pointer-events-none fixed z-50 max-w-[320px] rounded-lg border border-border bg-tip px-2.5 py-1.5 text-xs opacity-0 shadow-[0_8px_24px_rgba(59,13,42,.18)] transition-opacity duration-100',
        idea && 'opacity-100',
      )}
    >
      {idea ? (
        <>
          <strong>{idea.title}</strong>
          {idea.pitch ? <p className="my-1 text-subtle">{idea.pitch}</p> : null}
          {sum != null ? <div className="text-muted-foreground">{`${sum}/15 ${m ? MEDAL_ICON[m] : ''}`}</div> : null}
        </>
      ) : null}
    </div>
  )
}

function SeedBar() {
  const { exhibit } = useStore()
  const [value, setValue] = useState('')
  const [busy, setBusy] = useState<string | null>(null)
  const ref = useRef<HTMLInputElement>(null)
  useEffect(() => {
    setSeedInput(ref.current)
    return () => setSeedInput(null)
  }, [])
  const wasBusy = useRef(false)
  useEffect(() => {
    // Back in the input once the seed is in (it was disabled while busy).
    if (!busy && wasBusy.current) ref.current?.focus()
    wasBusy.current = !!busy
  }, [busy])
  async function submit(e: React.FormEvent) {
    e.preventDefault()
    const text = value.trim()
    if (!text || !S.board) return
    const isLink = /^(https?:\/\/|www\.)\S+$/i.test(text)
    setValue('')
    setBusy(isLink ? t('readingPage') : text.length > 160 ? t('thinking') : t('seedPlaceholder'))
    try {
      await seed(text)
    } catch (err) {
      setValue(text)
      fail(err)
    } finally {
      setBusy(null)
    }
  }
  return (
    <form
      onSubmit={submit}
      autoComplete="off"
      className="absolute bottom-4.5 left-1/2 w-[min(720px,calc(100%-40px))] -translate-x-1/2"
    >
      <input
        ref={ref}
        value={value}
        disabled={!!busy}
        onChange={(e) => setValue(e.target.value)}
        placeholder={busy ?? t('seedPlaceholder')}
        aria-label="Seed"
        className={cn(
          'w-full rounded-[14px] border border-border bg-card/94 px-4.5 py-3 text-[15px] shadow-[0_12px_34px_rgba(59,13,42,.18)] outline-none placeholder:text-muted-foreground',
          'focus:border-primary focus:shadow-[0_0_0_3px_rgba(194,69,124,.25),0_12px_34px_rgba(59,13,42,.18)]',
          exhibit && 'px-5.5 py-4.5 text-xl',
        )}
      />
    </form>
  )
}

function ExhibitOverlay() {
  const { board } = useStore()
  const top = ideaList().filter((i) => fitness(i) != null).sort((a, b) => fitness(b)! - fitness(a)!).slice(0, 3)
  return (
    <div
      aria-hidden="true"
      className="pointer-events-none absolute top-3.5 left-4 max-w-[min(660px,52vw)] rounded-[14px] px-4.5 pt-3 pb-3.5 [text-shadow:0_0_12px_rgba(255,255,255,.95)]"
      style={{ background: 'radial-gradient(ellipse at top left, rgba(255,255,255,.92) 40%, rgba(255,255,255,0) 75%)' }}
    >
      <h1 className="m-0 text-[44px] font-bold tracking-tight">{board?.title}</h1>
      <p className="mt-1.5 mb-3 text-xl text-subtle">{board?.goal}</p>
      <p className="text-[15px] text-good">{autopilotChip().text}</p>
      <ol className="list-none p-0 text-lg">
        {top.map((i) => (
          <li key={i.id} className="my-1.5">
            <span>{`${medal(i) ? MEDAL_ICON[medal(i)!] : ''} ${scoreSum(i) ?? ''}`}</span> {i.title}
          </li>
        ))}
      </ol>
    </div>
  )
}

export function Board() {
  const { exhibit } = useStore()
  const canvas = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    const g = new Graph(canvas.current!, graphHandlers)
    setGraph(g)
    return () => {
      g.destroy()
      setGraph(null)
    }
  }, [])
  const zoomBtn = 'text-subtle hover:text-foreground'
  return (
    <div className="relative min-w-0 flex-1">
      <canvas ref={canvas} className="absolute inset-0 block size-full cursor-grab touch-none" />
      {!exhibit ? (
        <div className="absolute bottom-19 left-3 grid gap-1 rounded-lg border bg-card/85 p-0.5">
          <Button variant="ghost" size="icon" className={zoomBtn} aria-label="+" onClick={() => graph?.zoomBy(1.25)}>＋</Button>
          <Button variant="ghost" size="icon" className={zoomBtn} aria-label="−" onClick={() => graph?.zoomBy(0.8)}>－</Button>
          <Button variant="ghost" size="icon" className={zoomBtn} title={t('fit')} aria-label={t('fit')} onClick={() => graph?.fit()}>⤢</Button>
        </div>
      ) : null}
      <SeedBar />
      {exhibit ? <ExhibitOverlay /> : null}
      <HoverCard />
    </div>
  )
}
