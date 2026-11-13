import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { SidebarTrigger } from '@/components/ui/sidebar'
import { t } from '@/i18n'
import {
  autopilotChip, fail, openBoard, patchBoard, useStore,
} from '@/store'

/** Title and goal: edited in place, saved on change (blur or Enter), like upstream's `change` event. */
function InlineEdit({ value, onSave, className, ...rest }: {
  value: string; onSave: (v: string) => void; className: string; placeholder?: string; title?: string; 'aria-label': string
}) {
  return (
    <Input
      key={value}
      defaultValue={value}
      spellCheck={false}
      onBlur={(e) => e.target.value !== value && onSave(e.target.value)}
      onKeyDown={(e) => e.key === 'Enter' && e.currentTarget.blur()}
      className={cn(
        'h-auto min-w-0 rounded-lg border-transparent bg-transparent px-2 py-1 text-sm dark:bg-transparent',
        'hover:border-border focus-visible:border-primary focus-visible:bg-card focus-visible:ring-0',
        className,
      )}
      {...rest}
    />
  )
}

export function TopBar() {
  const { board } = useStore()
  const auto = board?.config.mode === 'auto'
  const chip = autopilotChip()
  const ghost = 'text-subtle hover:text-foreground'
  return (
    <header className="z-5 flex min-w-0 items-center justify-between gap-3 overflow-hidden border-b bg-background/92 px-3 py-2">
      <div className="flex min-w-0 flex-auto items-center gap-1.5">
        <SidebarTrigger className={ghost} />
        {board ? (
          <>
            <InlineEdit
              value={board.title}
              aria-label={t('title')}
              title={t('title')}
              className="w-[170px] flex-[0_1_170px] font-bold"
              onSave={(v) => v.trim() && patchBoard({ title: v })}
            />
            <InlineEdit
              value={board.goal}
              aria-label={t('goal')}
              title={board.goal || t('goal')}
              placeholder={t('noGoal')}
              className="flex-[1_1_220px] text-ellipsis text-subtle"
              onSave={(v) => patchBoard({ goal: v })}
            />
            <span className="max-w-[220px] min-w-0 flex-[0_1_auto] overflow-hidden">
              {board.originBoardId ? (
                <button
                  type="button"
                  className="block max-w-full cursor-pointer truncate text-left text-xs text-primary hover:underline"
                  onClick={() => openBoard(board.originBoardId!).catch(fail)}
                >
                  {`↳ ${board.originIdeaTitle} · ${board.originBoardTitle}`}
                </button>
              ) : null}
            </span>
          </>
        ) : null}
      </div>
      <div className="flex min-w-0 flex-[0_1_auto] items-center gap-1.5">
        <Button
          variant="outline"
          aria-pressed={auto}
          disabled={!board}
          onClick={() => patchBoard({ config: { mode: auto ? 'manual' : 'auto' } })}
          className={cn('rounded-full px-3', auto && 'border-good bg-good/16 text-emerald-800 hover:bg-good/25 dark:border-good dark:bg-good/16 dark:hover:bg-good/25')}
        >
          {auto ? t('auto') : t('manual')}
        </Button>
        <span
          aria-live="polite"
          title={chip.title}
          className={cn('max-w-[150px] flex-[0_1_auto] truncate text-xs text-subtle', chip.tone === 'warn' && 'text-gold')}
        >
          {chip.tone === 'busy' ? <span className="text-good">● </span> : null}
          {chip.text}
        </span>
      </div>
    </header>
  )
}
