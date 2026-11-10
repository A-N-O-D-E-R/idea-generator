import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { getLang, setLang, t } from '@/i18n'
import {
  S, autopilotChip, emit, enterExhibit, exportBoard, fail, openBoard, openDialog, patchBoard, useStore,
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
  const { boards, board } = useStore()
  const auto = board?.config.mode === 'auto'
  const chip = autopilotChip()
  const ghost = 'text-subtle hover:text-foreground'
  return (
    <header className="z-5 flex min-w-0 items-center justify-between gap-3 overflow-hidden border-b bg-background/92 px-3 py-2">
      <div className="flex min-w-0 flex-auto items-center gap-1.5">
        <span className="mr-1 font-extrabold tracking-tight whitespace-nowrap">{t('appName')}</span>
        <DropdownMenu>
          <DropdownMenuTrigger render={<Button variant="ghost" className={ghost} />}>{`${t('boards')} ▾`}</DropdownMenuTrigger>
          {/* Portalled: floats above the board, not clipped by the bar. */}
          <DropdownMenuContent className="max-h-[60vh] w-auto min-w-70 p-1.5 shadow-[0_16px_40px_rgba(0,0,0,.45)]">
            {boards.map((b) => (
              <DropdownMenuItem
                key={b.id}
                onClick={() => openBoard(b.id).catch(fail)}
                className={cn('gap-2 px-2.5 py-2', b.id === board?.id && 'bg-selected')}
              >
                <span className={cn('size-2 flex-none rounded-full bg-[#3a4150]', b.config.mode === 'auto' && 'bg-good shadow-[0_0_0_3px_rgba(63,182,139,.18)]')} />
                <span className="min-w-0 flex-1 truncate">{b.title}</span>
                <span className="text-muted-foreground">{String(b.ideaCount ?? '')}</span>
              </DropdownMenuItem>
            ))}
            <DropdownMenuSeparator />
            <DropdownMenuItem className="px-2.5 py-2" onClick={() => openDialog({ kind: 'newBoard', first: false })}>
              {`＋ ${t('newBoard')}`}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
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
          className={cn('rounded-full px-3', auto && 'border-good bg-good/16 text-[#bff0dc] hover:bg-good/25 dark:border-good dark:bg-good/16 dark:hover:bg-good/25')}
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
        <Button variant="ghost" className={ghost} aria-expanded={S.leadsOpen} onClick={() => { S.leadsOpen = !S.leadsOpen; emit() }}>
          {t('leads')}
        </Button>
        <Button variant="ghost" className={ghost} onClick={() => { S.view = { kind: 'dashboard', keyword: null }; emit() }}>
          {t('dashboard')}
        </Button>
        <Button variant="ghost" className={ghost} onClick={() => { S.view = { kind: 'report' }; emit() }}>
          {t('report')}
        </Button>
        <Button variant="ghost" className={ghost} onClick={enterExhibit}>{t('exhibit')}</Button>
        <Button variant="ghost" className={ghost} disabled={!board} onClick={exportBoard}>{t('export')}</Button>
        <Button
          variant="ghost"
          size="icon"
          className={ghost}
          disabled={!board}
          title={t('boardSettings')}
          aria-label={t('boardSettings')}
          onClick={() => openDialog({ kind: 'boardSettings' })}
        >
          ⚙
        </Button>
        <Button
          variant="ghost"
          size="icon"
          className={ghost}
          title={t('appSettings')}
          aria-label={t('appSettings')}
          onClick={() => openDialog({ kind: 'appSettings' })}
        >
          🔑
        </Button>
        <Button
          variant="ghost"
          size="sm"
          className={ghost}
          onClick={() => { setLang(getLang() === 'sv' ? 'en' : 'sv'); emit() }}
        >
          {getLang() === 'sv' ? 'EN' : 'SV'}
        </Button>
      </div>
    </header>
  )
}
