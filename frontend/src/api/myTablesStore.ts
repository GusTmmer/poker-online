import { getMyTables, getTableInfo } from './client'
import { subscribeToSessionEvents } from './sessionEvents'
import type { TableSummary } from './types'

// Observable store backing the home-screen "Your tables" list.
//
// Discovery of tables from *prior* visits can only come from the server (the JWT
// cookies are httpOnly), so we hydrate once from `/api/my-tables`, TTL-guarded.
// But mutations made *this* session don't need that round-trip: we observe the
// create/join events the API client emits and apply their known effect directly —
// a create adds exactly one fully-known row; a join adds one table we then reconcile
// with a single targeted read of *that* table (never the broad list again).
const TTL_MS = 45_000

let tables: TableSummary[] = []
let hydratedAt = 0
let inflight: Promise<void> | null = null
const listeners = new Set<() => void>()

function emit() {
  for (const listener of listeners) listener()
}

function upsert(summary: TableSummary) {
  // New array identity so useSyncExternalStore sees a change.
  tables = [...tables.filter((t) => t.tableId !== summary.tableId), summary]
  emit()
}

export function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function getSnapshot(): TableSummary[] {
  return tables
}

/** Discover sessions from earlier visits. Cheap-guarded by TTL + in-flight dedupe. */
export function hydrateMyTables(force = false): Promise<void> {
  if (!force && hydratedAt > 0 && Date.now() - hydratedAt < TTL_MS) {
    return Promise.resolve()
  }
  if (inflight) return inflight

  inflight = getMyTables()
    .then((res) => {
      tables = res.tables
      hydratedAt = Date.now()
      emit()
    })
    // Discovery is an enhancement, not a gate — swallow errors and show nothing.
    .catch(() => {})
    .finally(() => {
      inflight = null
    })
  return inflight
}

// Observe session changes emitted by the API client and fold them into the list.
subscribeToSessionEvents((event) => {
  if (event.type === 'created') {
    upsert(event.summary)
    return
  }
  if (event.type === 'renamed') {
    const existing = tables.find((t) => t.tableId === event.tableId)
    if (existing) upsert({ ...existing, name: event.name })
    return
  }
  if (event.type === 'left') {
    tables = tables.filter((t) => t.tableId !== event.tableId)
    emit()
    return
  }
  // 'joined': we know which table changed but not its live seat count/status, so we
  // reconcile with one precise read of that table — not the whole my-tables list.
  getTableInfo(event.tableId)
    .then((info) => {
      upsert({
        tableId: event.tableId,
        name: info.name,
        playerName: event.playerName,
        gameStatus: info.gameStatus,
        playerCount: info.players.length,
        maxPlayers: info.maxPlayers,
      })
    })
    .catch(() => {})
})
