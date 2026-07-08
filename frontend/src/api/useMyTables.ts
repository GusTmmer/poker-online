import { useEffect, useSyncExternalStore } from 'react'
import { getSnapshot, hydrateMyTables, subscribe } from './myTablesStore'

// Thin React binding over the observable store. Mounting hydrates once (TTL-guarded);
// create/join afterwards update the store via the session-event observer, so this hook
// never re-hits `/api/my-tables` on a mutation — it just re-renders from the store.
export function useMyTables() {
  const tables = useSyncExternalStore(subscribe, getSnapshot)

  useEffect(() => {
    void hydrateMyTables()
  }, [])

  return { tables, refresh: () => hydrateMyTables(true) }
}
