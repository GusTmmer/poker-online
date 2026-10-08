import { createContext, useContext } from 'react'
import type { TableInfoResponse } from '../api/types'

export interface SessionContextValue {
  tableId: number
  myPlayerId: number | null
  tableInfo: TableInfoResponse | null
  loading: boolean
  error: string | null
  join: (playerName: string) => Promise<void>
  refresh: () => Promise<void>
}

export const SessionContext = createContext<SessionContextValue | null>(null)

export function useSession() {
  const ctx = useContext(SessionContext)
  if (!ctx) throw new Error('useSession must be used within a SessionProvider')
  return ctx
}
