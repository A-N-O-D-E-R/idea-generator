import { useEffect } from 'react'
import { Toaster } from '@/components/ui/sonner'
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
    // The hidden top bar leaves the grid entirely in exhibition mode, so main is the only row.
    <div className={exhibit ? 'grid h-full grid-rows-[1fr]' : 'grid h-full grid-rows-[auto_1fr]'}>
      {!exhibit ? <TopBar /> : null}
      <main className="flex min-h-0">
        {leadsOpen && !exhibit ? <LeadsPanel /> : null}
        <Board />
        {!exhibit ? <IdeaPanel /> : null}
      </main>
      {view?.kind === 'dashboard' ? <Dashboard key={view.keyword ?? ''} keyword={view.keyword} /> : null}
      {view?.kind === 'report' ? <Report /> : null}
      {dialog?.kind === 'newBoard' ? <NewBoardDialog first={dialog.first} /> : null}
      {dialog?.kind === 'boardSettings' && board ? <BoardSettings /> : null}
      {dialog?.kind === 'appSettings' ? <AppSettings /> : null}
      {dialog?.kind === 'announce' ? <AnnounceDialog idea={dialog.idea} /> : null}
      <ConfirmDialog />
      <ModelList />
      <Toaster theme="dark" position="bottom-right" />
    </div>
  )
}
