// The sign-in screen of a shared server (ideagen.auth=oauth), shown when the API answers 401.

import { buttonVariants } from '@/components/ui/button'
import { t } from '@/i18n'

const LABELS: Record<string, string> = { github: 'GitHub', google: 'Google' }

export function SignIn({ providers }: { providers: string[] }) {
  const failed = new URLSearchParams(location.search).has('signin')
  return (
    <div
      className="grid h-full place-items-center p-6"
      style={{ background: 'radial-gradient(circle at 15% 10%, #1a0614 0%, #3b0d2a 40%, #5a1440 65%, #4b1d7a 100%)' }}
    >
      <div className="grid w-full max-w-sm gap-4 rounded-xl border border-white/15 bg-black/30 p-8 text-center shadow-2xl backdrop-blur">
        <div className="flex justify-center gap-1.5">
          {['M', 'U', 'S', 'E'].map((c) => (
            <div key={c} className="flex size-10 items-center justify-center rounded-full border-2 border-white text-base font-bold text-white">{c}</div>
          ))}
        </div>
        <p className="text-xs tracking-wider text-white/80">{t('appTagline')}</p>
        <p className="text-sm text-white/70">{t('signInNote')}</p>
        {failed ? <p className="text-sm text-gold">{t('signInFailed')}</p> : null}
        {providers.map((p) => (
          // A full page load: the server redirects to the provider and back.
          <a key={p} className={buttonVariants({ variant: 'outline' }) + ' text-white!'} href={`/oauth2/authorization/${encodeURIComponent(p)}`}>
            {t('signInWith', { p: LABELS[p] ?? p })}
          </a>
        ))}
      </div>
    </div>
  )
}
