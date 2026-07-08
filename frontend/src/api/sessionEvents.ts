import type { TableSummary } from './types'

// A tiny observer bus for "this browser's set of table sessions changed" events.
// The API client (the subject) emits these the moment a create/join succeeds; the
// my-tables store (the observer) reduces them into UI state. Kept as a leaf module
// — it imports nothing but types — so client ↔ store never form an import cycle.
export type SessionEvent =
  | { type: 'created'; summary: TableSummary }
  | { type: 'joined'; tableId: number; playerName: string }
  | { type: 'renamed'; tableId: number; name: string }
  | { type: 'left'; tableId: number }

type Listener = (event: SessionEvent) => void

const listeners = new Set<Listener>()

export function subscribeToSessionEvents(listener: Listener): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function emitSessionEvent(event: SessionEvent): void {
  for (const listener of listeners) listener(event)
}
