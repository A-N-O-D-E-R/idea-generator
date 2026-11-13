import { useEffect, type CSSProperties } from 'react'
import { Toaster } from '@/components/ui/sonner'
import { SidebarInset, SidebarProvider } from '@/components/ui/sidebar'
import { AppSidebar } from '@/components/AppSidebar'
import { TopBar } from '@/components/TopBar'
import { LeadsPanel } from '@/components/LeadsPanel'
import { Board } from '@/components/Board'
import { IdeaPanel } from '@/components/IdeaPanel'
import { Dashboard } from '@/components/Dashboard'
import { Report } from '@/components/Report'
import { BoardSettings } from '@/components/BoardSettings'
import { AppSettings, ModelList } from '@/components/AppSettings'
import { AnnounceDialog, ConfirmDialog, NewBoardDialog } from '@/components/Dialogs'
import { SignIn } from '@/components/SignIn'
import { fail, main, useStore } from '@/store'

export default function App() {
  const { exhibit, leadsOpen, view, dialog, board, signIn } = useStore()
  useEffect(() => {
    main().catch(fail)
  }, [])
  if (signIn) return <SignIn providers={signIn} />
  return (
    <SidebarProvider className="h-full min-h-0" style={{ '--sidebar-width': '14rem' } as CSSProperties}>
      {/* Exhibition mode: no sidebar, no top bar; the board fills the window. */}
      {!exhibit ? <AppSidebar /> : null}
      <SidebarInset className="min-h-0 min-w-0 bg-background">
        <div className={exhibit ? 'grid h-full grid-rows-[1fr]' : 'grid h-full grid-rows-[auto_1fr]'}>
          {!exhibit ? <TopBar /> : null}
          <main className="flex min-h-0">
            {leadsOpen && !exhibit ? <LeadsPanel /> : null}
            <Board />
            {!exhibit ? <IdeaPanel /> : null}
          </main>
        </div>
      </SidebarInset>
      {view?.kind === 'dashboard' ? <Dashboard key={view.keyword ?? ''} keyword={view.keyword} /> : null}
      {view?.kind === 'report' ? <Report /> : null}
      {dialog?.kind === 'newBoard' ? <NewBoardDialog first={dialog.first} /> : null}
      {dialog?.kind === 'boardSettings' && board ? <BoardSettings /> : null}
      {dialog?.kind === 'appSettings' ? <AppSettings /> : null}
      {dialog?.kind === 'announce' ? <AnnounceDialog idea={dialog.idea} /> : null}
      <ConfirmDialog />
      <ModelList />
      <Toaster theme="light" position="bottom-right" />
    </SidebarProvider>
  )
}
