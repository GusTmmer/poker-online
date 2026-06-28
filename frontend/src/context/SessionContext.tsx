import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { getTableInfo, joinTable } from '../api/client'
import type { TableInfoResponse } from '../api/types'

interface SessionContextValue {
  tableId: number
  myPlayerId: number | null
  tableInfo: TableInfoResponse | null
  loading: boolean
  error: string | null
  join: (playerName: string) => Promise<void>
  refresh: () => Promise<void>
}

const SessionContext = createContext<SessionContextValue | null>(null)

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

  useEffect(() => {
    refresh()
  }, [refresh])

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

export function useSession() {
  const ctx = useContext(SessionContext)
  if (!ctx) throw new Error('useSession must be used within a SessionProvider')
  return ctx
}
