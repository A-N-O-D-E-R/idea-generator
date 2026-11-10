// Fetch wrapper and the live event stream for a board.

import type { BoardEvent } from './types'

export type ApiError = Error & { code?: string; status?: number }

export async function api<T = unknown>(method: string, url: string, body?: unknown): Promise<T> {
  const res = await fetch(url, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : {},
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  const isJson = res.headers.get('content-type')?.includes('json')
  const data = isJson ? await res.json() : await res.text()
  if (!res.ok) {
    const err: ApiError = new Error((isJson && data?.error) || res.statusText)
    err.code = isJson ? data?.code : 'http_' + res.status
    err.status = res.status
    throw err
  }
  return data as T
}

/** Follow everything that happens on a board. Returns a function that stops following. */
export function follow(boardId: number, onEvent: (e: BoardEvent) => void) {
  const es = new EventSource(`/api/boards/${boardId}/events`)
  es.onmessage = (m) => {
    try {
      onEvent(JSON.parse(m.data))
    } catch (e) {
      console.error('bad event', e)
    }
  }
  return () => es.close()
}

/** Save text as a file (Markdown exports are downloaded, never rendered). */
export function download(text: string, name: string) {
  const a = document.createElement('a')
  a.href = URL.createObjectURL(new Blob([text], { type: 'text/markdown' }))
  a.download = name
  a.click()
  URL.revokeObjectURL(a.href)
}

/** Links written by models or visitors: only http(s) becomes a link. */
export const safeHref = (url: string | null | undefined) => (url && /^https?:\/\//i.test(url) ? url : undefined)
