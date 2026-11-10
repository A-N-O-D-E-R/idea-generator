// Small building blocks shared by the panels and dialogs.

import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Dialog, DialogClose, DialogContent, DialogTitle } from '@/components/ui/dialog'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { KIND_COLORS } from '@/lib/graph'
import { t } from '@/i18n'

export function Field({ label, note, children }: { label: string; note?: string; children: ReactNode }) {
  return (
    <label className="my-2 grid gap-1">
      <span className="text-xs text-subtle">{label}</span>
      {children}
      {note ? <small className="text-muted-foreground">{note}</small> : null}
    </label>
  )
}

export function Fieldset({ legend, children }: { legend: string; children: ReactNode }) {
  return (
    <fieldset className="mb-3.5 rounded-lg border px-3.5 pt-2.5 pb-3">
      <legend className="px-1.5 font-semibold text-subtle">{legend}</legend>
      {children}
    </fieldset>
  )
}

export function Sel<V extends string | number>({
  value, onChange, items, className,
}: { value: V; onChange: (v: V) => void; items: { value: V; label: string }[]; className?: string }) {
  return (
    <Select items={items} value={value} onValueChange={(v) => v != null && onChange(v as V)}>
      <SelectTrigger className={cn('w-full', className)}>
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {items.map((x) => (
          <SelectItem key={String(x.value)} value={x.value}>{x.label}</SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}

/** A segmented-control button (medal override, period). */
export function Seg({ on, onClick, children }: { on: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <Button
      variant="outline"
      size="sm"
      aria-pressed={on}
      onClick={onClick}
      className={cn('bg-transparent text-subtle dark:bg-transparent', on && 'border-primary bg-selected text-foreground dark:border-primary dark:bg-selected')}
    >
      {children}
    </Button>
  )
}

export function Swatch({ kind }: { kind: string }) {
  return <span className="inline-block size-2.5 flex-none rounded-full" style={{ background: KIND_COLORS[kind] }} />
}

/** Rank row: a title, optional context, a score; used by overview, dashboard and report. */
export function RankItem({ onClick, children }: { onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex w-full min-w-0 cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-left hover:bg-muted"
    >
      {children}
    </button>
  )
}

export const Grow = ({ children }: { children: ReactNode }) => (
  <span className="min-w-0 flex-1 truncate">{children}</span>
)

/** The modal shell: title bar, scrolling body, footer. */
export function Dlg({
  title, onClose, children, footer,
}: { title: string; onClose: () => void; children: ReactNode; footer?: ReactNode }) {
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent
        showCloseButton={false}
        className="flex max-h-[calc(100vh-48px)] w-[min(760px,calc(100vw-32px))] max-w-none flex-col gap-0 rounded-[14px] bg-card p-0 sm:max-w-none"
      >
        <header className="flex items-center justify-between border-b px-4.5 py-3.5">
          <DialogTitle className="text-[17px] font-semibold">{title}</DialogTitle>
          <DialogClose render={<Button variant="ghost" size="icon" aria-label={t('close')} />}>✕</DialogClose>
        </header>
        <div className="overflow-auto px-4.5 py-3.5">{children}</div>
        {footer ? <footer className="flex justify-end gap-2 border-t px-4.5 py-3">{footer}</footer> : null}
      </DialogContent>
    </Dialog>
  )
}
