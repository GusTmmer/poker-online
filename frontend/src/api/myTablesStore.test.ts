import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { TableSummary } from './types'

// The store imports getMyTables/getTableInfo from ./client; mock both. vi.hoisted so the fns
// exist before the (hoisted) vi.mock factory runs, and are stable across vi.resetModules().
const { getMyTables, getTableInfo } = vi.hoisted(() => ({
  getMyTables: vi.fn(),
  getTableInfo: vi.fn(),
}))
vi.mock('./client', () => ({ getMyTables, getTableInfo }))

// The store is a module singleton that subscribes to sessionEvents at import time. Reset the module
// registry per test for a clean store, and pull sessionEvents from the *same* fresh graph so an emit
// reaches the listener the store just registered.
async function freshStore() {
  vi.resetModules()
  const store = await import('./myTablesStore')
  const events = await import('./sessionEvents')
  return { store, events }
}

// Drain the microtask + macrotask queue so a 'joined' event's getTableInfo().then() settles.
const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0))

function summary(over: Partial<TableSummary> & { tableId: number }): TableSummary {
  return { name: '', playerName: 'Me', gameStatus: 'WAITING', playerCount: 1, maxPlayers: 6, ...over }
}

beforeEach(() => {
  getMyTables.mockReset()
  getTableInfo.mockReset()
})

describe('myTablesStore', () => {
  describe('hydrate', () => {
    it('loads the list from the server and notifies subscribers', async () => {
      const { store } = await freshStore()
      getMyTables.mockResolvedValue({ tables: [summary({ tableId: 1 }), summary({ tableId: 2 })] })
      const listener = vi.fn()
      store.subscribe(listener)

      await store.hydrateMyTables()

      expect(store.getSnapshot().map((t) => t.tableId).sort()).toEqual([1, 2])
      expect(listener).toHaveBeenCalled()
    })

    it('does not re-hydrate within the TTL, but force overrides', async () => {
      const { store } = await freshStore()
      getMyTables.mockResolvedValue({ tables: [] })

      await store.hydrateMyTables()
      await store.hydrateMyTables()
      expect(getMyTables).toHaveBeenCalledTimes(1)

      await store.hydrateMyTables(true)
      expect(getMyTables).toHaveBeenCalledTimes(2)
    })

    it('dedupes concurrent hydrate calls into one request', async () => {
      const { store } = await freshStore()
      let resolve: (value: { tables: TableSummary[] }) => void = () => {}
      getMyTables.mockReturnValue(new Promise((r) => { resolve = r }))

      const first = store.hydrateMyTables()
      const second = store.hydrateMyTables()
      resolve({ tables: [] })
      await Promise.all([first, second])

      expect(getMyTables).toHaveBeenCalledTimes(1)
    })

    it('swallows errors and leaves the list empty', async () => {
      const { store } = await freshStore()
      getMyTables.mockRejectedValue(new Error('network'))

      await expect(store.hydrateMyTables()).resolves.toBeUndefined()
      expect(store.getSnapshot()).toEqual([])
    })
  })

  describe('session-event reducer', () => {
    it('applies a created event directly, without any server call', async () => {
      const { store, events } = await freshStore()

      events.emitSessionEvent({ type: 'created', summary: summary({ tableId: 5, name: 'Friday' }) })

      expect(store.getSnapshot()).toEqual([summary({ tableId: 5, name: 'Friday' })])
      expect(getMyTables).not.toHaveBeenCalled()
      expect(getTableInfo).not.toHaveBeenCalled()
    })

    it('keeps one row per table id when the same table is upserted twice', async () => {
      const { store, events } = await freshStore()

      events.emitSessionEvent({ type: 'created', summary: summary({ tableId: 5, playerCount: 1 }) })
      events.emitSessionEvent({ type: 'created', summary: summary({ tableId: 5, playerCount: 2 }) })

      expect(store.getSnapshot()).toHaveLength(1)
      expect(store.getSnapshot()[0].playerCount).toBe(2)
    })

    it('renames an existing table and ignores a rename for an unknown one', async () => {
      const { store, events } = await freshStore()
      events.emitSessionEvent({ type: 'created', summary: summary({ tableId: 5, name: 'Old' }) })

      events.emitSessionEvent({ type: 'renamed', tableId: 5, name: 'New' })
      expect(store.getSnapshot().find((t) => t.tableId === 5)?.name).toBe('New')

      events.emitSessionEvent({ type: 'renamed', tableId: 999, name: 'Ghost' })
      expect(store.getSnapshot().map((t) => t.tableId)).toEqual([5])
    })

    it('removes a table on a left event', async () => {
      const { store, events } = await freshStore()
      events.emitSessionEvent({ type: 'created', summary: summary({ tableId: 5 }) })

      events.emitSessionEvent({ type: 'left', tableId: 5 })

      expect(store.getSnapshot()).toEqual([])
    })

    // Regression guard: the joined-reconcile once dropped info.name, which both broke the build
    // (required field) and would have shown "Table N" instead of the real name.
    it('reconciles a joined table from getTableInfo, carrying its name', async () => {
      const { store, events } = await freshStore()
      getTableInfo.mockResolvedValue({
        tableId: 8,
        name: 'Reconciled',
        players: [{}, {}],
        maxPlayers: 4,
        gameStatus: 'RUNNING',
      })

      events.emitSessionEvent({ type: 'joined', tableId: 8, playerName: 'Me' })
      await flush()

      expect(store.getSnapshot().find((t) => t.tableId === 8)).toEqual(
        summary({
          tableId: 8,
          name: 'Reconciled',
          playerName: 'Me',
          gameStatus: 'RUNNING',
          playerCount: 2,
          maxPlayers: 4,
        }),
      )
    })
  })
})
