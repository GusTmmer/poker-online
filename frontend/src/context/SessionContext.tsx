import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { getTableInfo, joinTable } from '../api/client'
import type { TableInfoResponse } from '../api/types'
import { SessionContext, type SessionContextValue } from './useSession'

/** One table's session. Keyed by table id where it's rendered, so a different table starts from scratch. */
export function SessionProvider({ tableId, children }: { tableId: number; children: ReactNode }) {
  const [tableInfo, setTableInfo] = useState<TableInfoResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const info = await getTableInfo(tableId)
      setTableInfo(info)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load table')
    } finally {
      setLoading(false)
    }
  }, [tableId])

  // The first load. It ignores a response that lands after unmount (e.g. leaving for another table).
  useEffect(() => {
    let current = true
    getTableInfo(tableId)
      .then((info) => { if (current) setTableInfo(info) })
      .catch((e) => { if (current) setError(e instanceof Error ? e.message : 'Failed to load table') })
      .finally(() => { if (current) setLoading(false) })
    return () => { current = false }
  }, [tableId])

  const join = useCallback(
    async (playerName: string) => {
      setError(null)
      try {
        await joinTable(tableId, { playerName })
        await refresh()
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Failed to join table')
        throw e
      }
    },
    [tableId, refresh],
  )

  const myPlayerId = tableInfo?.sessionPlayerId ?? null

  const value = useMemo<SessionContextValue>(
    () => ({ tableId, myPlayerId, tableInfo, loading, error, join, refresh }),
    [tableId, myPlayerId, tableInfo, loading, error, join, refresh],
  )

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}
