// The sign-in screen of a shared server (ideagen.auth=oauth), shown when the API answers 401.

import { buttonVariants } from '@/components/ui/button'
import { t } from '@/i18n'

const LABELS: Record<string, string> = { github: 'GitHub', google: 'Google' }

export function SignIn({ providers }: { providers: string[] }) {
  const failed = new URLSearchParams(location.search).has('signin')
  return (
    <div className="grid h-full place-items-center p-6">
      <div className="grid w-full max-w-sm gap-4 rounded-xl border bg-card p-6 text-center">
        <h1 className="text-xl font-extrabold tracking-tight">{t('appName')}</h1>
        <p className="text-sm text-subtle">{t('signInNote')}</p>
        {failed ? <p className="text-sm text-gold">{t('signInFailed')}</p> : null}
        {providers.map((p) => (
          // A full page load: the server redirects to the provider and back.
          <a key={p} className={buttonVariants()} href={`/oauth2/authorization/${encodeURIComponent(p)}`}>
            {t('signInWith', { p: LABELS[p] ?? p })}
          </a>
        ))}
      </div>
    </div>
  )
}
