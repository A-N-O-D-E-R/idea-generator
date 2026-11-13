import { BarChart3, Download, FileText, KeyRound, Languages, LogOut, Plus, Presentation, Radar, SlidersHorizontal, User } from 'lucide-react'
import type { ComponentType } from 'react'
import {
  Sidebar, SidebarContent, SidebarFooter, SidebarGroup, SidebarGroupContent, SidebarGroupLabel, SidebarHeader,
  SidebarMenu, SidebarMenuButton, SidebarMenuItem, SidebarRail, SidebarSeparator,
} from '@/components/ui/sidebar'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { cn } from '@/lib/utils'
import { getLang, setLang, t } from '@/i18n'
import { S, emit, enterExhibit, exportBoard, fail, openBoard, openDialog, signOut, useStore } from '@/store'

type Item = { label: string; icon: ComponentType; onClick: () => void; disabled?: boolean; active?: boolean }

const WORD = ['M', 'U', 'S', 'E']

function Group({ label, items }: { label: string; items: Item[] }) {
  return (
    <SidebarGroup className="p-0">
      <SidebarSeparator className="hidden bg-white/20 group-data-[collapsible=icon]:mx-2 group-data-[collapsible=icon]:my-3 group-data-[collapsible=icon]:block group-data-[collapsible=icon]:w-auto" />
      <div className="flex items-center py-2 group-data-[collapsible=icon]:hidden">
        <div className="flex-1 border-t border-white/20" />
        <SidebarGroupLabel className="mx-2 text-xs tracking-wider text-white/80 uppercase">{label}</SidebarGroupLabel>
        <div className="flex-1 border-t border-white/20" />
      </div>
      <SidebarGroupContent>
        <SidebarMenu>
          {items.map((i) => (
            <SidebarMenuItem key={i.label}>
              <SidebarMenuButton
                tooltip={i.label}
                isActive={i.active}
                disabled={i.disabled}
                onClick={i.onClick}
                className="text-white hover:bg-white/10 hover:text-white data-active:bg-white/25 data-active:font-semibold data-active:text-white"
              >
                <i.icon />
                <span>{i.label}</span>
              </SidebarMenuButton>
            </SidebarMenuItem>
          ))}
        </SidebarMenu>
      </SidebarGroupContent>
    </SidebarGroup>
  )
}

export function AppSidebar() {
  const { boards, board, me } = useStore()
  const view = S.view?.kind
  const boardItems: Item[] = [
    ...boards.map((b): Item => ({
      label: b.title,
      icon: () => <span className={cn('size-2 rounded-full bg-white/40', b.config.mode === 'auto' && 'bg-emerald-400 shadow-[0_0_0_3px_rgba(52,211,153,.25)]')} />,
      active: b.id === board?.id,
      onClick: () => openBoard(b.id).catch(fail),
    })),
    { label: t('newBoard'), icon: Plus, onClick: () => openDialog({ kind: 'newBoard', first: false }) },
  ]
  const views: Item[] = [
    { label: t('leads'), icon: Radar, active: S.leadsOpen, onClick: () => { S.leadsOpen = !S.leadsOpen; emit() } },
    { label: t('dashboard'), icon: BarChart3, active: view === 'dashboard', onClick: () => { S.view = { kind: 'dashboard', keyword: null }; emit() } },
    { label: t('report'), icon: FileText, active: view === 'report', onClick: () => { S.view = { kind: 'report' }; emit() } },
    { label: t('exhibit'), icon: Presentation, onClick: enterExhibit },
    { label: t('export'), icon: Download, disabled: !board, onClick: exportBoard },
  ]
  const settings: Item[] = [
    { label: t('boardSettings'), icon: SlidersHorizontal, disabled: !board, onClick: () => openDialog({ kind: 'boardSettings' }) },
    { label: t('appSettings'), icon: KeyRound, onClick: () => openDialog({ kind: 'appSettings' }) },
    { label: getLang() === 'sv' ? 'English' : 'Svenska', icon: Languages, onClick: () => { setLang(getLang() === 'sv' ? 'en' : 'sv'); emit() } },
  ]
  return (
    <Sidebar
      collapsible="icon"
      className="[&_[data-slot='sidebar-inner']]:bg-transparent"
      style={{ background: 'radial-gradient(circle at 15% 10%, #1a0614 0%, #3b0d2a 40%, #5a1440 65%, #4b1d7a 100%)' }}
    >
      <SidebarHeader className="p-2">
        <div className="flex flex-col items-center">
          <div className="flex items-center justify-center gap-1.5">
            {WORD.map((c, i) => (
              <div
                key={c}
                className={cn('flex size-8 shrink-0 items-center justify-center rounded-full border-2 border-white text-sm font-bold text-white', i > 0 && 'group-data-[collapsible=icon]:hidden')}
              >
                {c}
              </div>
            ))}
          </div>
          <p className="mt-1 text-center text-[0.55rem] leading-tight tracking-wider text-white/80 group-data-[collapsible=icon]:hidden">
            {t('appTagline')}
          </p>
        </div>
      </SidebarHeader>
      <SidebarContent className="gap-2 overflow-x-hidden p-2">
        <Group label={t('boards')} items={boardItems} />
        <Group label={t('overview')} items={views} />
        <Group label={t('appSettings')} items={settings} />
      </SidebarContent>
      {me?.mode === 'oauth' ? (
        <SidebarFooter className="p-2">
          <DropdownMenu>
            <DropdownMenuTrigger
              render={<SidebarMenuButton tooltip={me.name || me.email || '?'} className="text-white hover:bg-white/10 hover:text-white" />}
            >
              <User />
              <span className="truncate">{me.name || me.email || '?'}</span>
            </DropdownMenuTrigger>
            <DropdownMenuContent side="right" align="end" className="w-auto min-w-44 p-1.5">
              {me.email ? <div className="px-2.5 py-1.5 text-xs text-muted-foreground">{me.email}</div> : null}
              <DropdownMenuItem className="gap-2 px-2.5 py-2" onClick={() => signOut().catch(fail)}>
                <LogOut />
                {t('signOut')}
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </SidebarFooter>
      ) : null}
      <SidebarRail />
    </Sidebar>
  )
}
