import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { t } from '@/i18n'
import { safeHref } from '@/api'
import { S, emit, refreshLeads, secondsSince, seedLead, useStore } from '@/store'
import type { LeadSource } from '@/types'

export function LeadsPanel() {
  const { board, leads, leadsTab: src, refreshing } = useStore()
  if (!board) return null
  const cfg = board.config.researchers[src]
  const busy = refreshing[src]
  const list = leads.filter((l) => l.source === src)
  return (
    <aside className="w-[340px] flex-none overflow-auto border-r bg-card px-4 pt-3.5 pb-6">
      <Tabs value={src} onValueChange={(v) => { S.leadsTab = v as LeadSource; emit() }} className="mb-3">
        <TabsList className="w-full">
          <TabsTrigger value="x">{t('leadsX')}</TabsTrigger>
          <TabsTrigger value="research">{t('leadsResearch')}</TabsTrigger>
        </TabsList>
      </Tabs>
      {!cfg.enabled ? (
        <p className="text-muted-foreground">{t('researcherOff')}</p>
      ) : (
        <>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="text-xs text-muted-foreground">
              {board.goal ? `${t('goal')}: ${board.goal}${cfg.focus ? ` — ${cfg.focus}` : ''}` : t('noGoal')}
            </span>
            <Button variant="secondary" size="sm" disabled={!!busy} onClick={() => refreshLeads(src)}>
              {busy ? `${t('refreshing')} ${secondsSince(busy)} s` : t('refresh')}
            </Button>
          </div>
          <p className={cn('my-3 text-muted-foreground', list.length && 'text-xs')}>{!list.length ? t('leadsEmpty') : t('dragLead')}</p>
          {list.map((l) => (
            <article
              key={l.id}
              draggable={!l.ideaId}
              onDragStart={(e) => {
                e.dataTransfer.setData('text/x-lead', String(l.id))
                e.dataTransfer.effectAllowed = 'copy'
              }}
              className={cn('my-2 cursor-grab rounded-lg border bg-muted px-3 py-2.5', l.ideaId && 'cursor-default opacity-55')}
            >
              <h4 className="mb-1 text-sm font-semibold">{l.title}</h4>
              {l.summary ? <p className="mb-1.5 text-[13px] text-subtle">{l.summary}</p> : null}
              {l.hook ? <p className="mb-1.5 text-[13px] text-primary">{`→ ${l.hook}`}</p> : null}
              <div className="flex flex-wrap items-center justify-between gap-2">
                {l.url ? (
                  <a href={safeHref(l.url)} target="_blank" rel="noopener noreferrer" className="text-xs">
                    {src === 'x' ? 'x.com ↗' : `${l.meta?.published ?? ''} ↗`}
                  </a>
                ) : <span />}
                {l.ideaId ? (
                  <span className="text-xs text-muted-foreground">{`✓ ${t('onBoard')}`}</span>
                ) : (
                  <Button variant="secondary" size="sm" onClick={() => seedLead(l.id)}>{t('addAsSeed')}</Button>
                )}
              </div>
            </article>
          ))}
        </>
      )}
    </aside>
  )
}
