import {
  ApiError,
  type ActionRequest,
  type CastVoteRequest,
  type CreateTableRequest,
  type CreateTableResponse,
  type CreateVoteSessionRequest,
  type JoinRequest,
  type JoinResponse,
  type MyTablesResponse,
  type KickRequest,
  type SettingsRequest,
  type StatusMessage,
  type TableInfoResponse,
  type VoteSessionResponse,
  type VoteSummary,
} from './types'
import { emitSessionEvent } from './sessionEvents'

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method,
    credentials: 'include',
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })

  const text = await response.text()
  let data: unknown
  try {
    data = text.length > 0 ? JSON.parse(text) : undefined
  } catch {
    throw new ApiError(response.status, `Unexpected response format`)
  }

  if (!response.ok) {
    const errorField = (data as { error?: unknown } | undefined)?.error
    const message = typeof errorField === 'string' ? errorField : response.statusText
    throw new ApiError(response.status, message)
  }

  return data as T
}

export async function createTable(req: CreateTableRequest) {
  const res = await request<CreateTableResponse>('POST', '/api/tables', req)
  // We know the exact effect: a fresh single-player WAITING table is now ours.
  emitSessionEvent({
    type: 'created',
    summary: {
      tableId: res.tableId,
      name: req.name?.trim() ?? '',
      playerName: req.playerName,
      gameStatus: 'WAITING',
      playerCount: 1,
      maxPlayers: req.maxPlayers ?? 6,
    },
  })
  return res
}

export function getMyTables() {
  return request<MyTablesResponse>('GET', '/api/my-tables')
}

export function getTableInfo(tableId: number) {
  return request<TableInfoResponse>('GET', `/api/tables/${tableId}`)
}

export async function joinTable(tableId: number, req: JoinRequest) {
  const res = await request<JoinResponse>('POST', `/api/tables/${tableId}/players`, req)
  // We know we now have a session for this table; the observer reconciles its live details.
  emitSessionEvent({ type: 'joined', tableId, playerName: req.playerName })
  return res
}

export async function leaveTable(tableId: number) {
  // Permanent leave: frees the seat server-side and clears the session cookie. Not recoverable.
  const res = await request<StatusMessage>('DELETE', `/api/tables/${tableId}/players/me`)
  emitSessionEvent({ type: 'left', tableId })
  return res
}

export function sendAction(tableId: number, req: ActionRequest) {
  return request<StatusMessage>('POST', `/api/tables/${tableId}/action`, req)
}

export function startRound(tableId: number) {
  return request<StatusMessage>('POST', `/api/tables/${tableId}/start-round`)
}

export function restartGame(tableId: number) {
  return request<StatusMessage>('POST', `/api/tables/${tableId}/restart-game`)
}

export function readyUp(tableId: number) {
  return request<StatusMessage>('POST', `/api/tables/${tableId}/ready`)
}

export function setPlayerOnline(tableId: number) {
  return request<StatusMessage>('POST', `/api/tables/${tableId}/activate`)
}

export function requestPause(tableId: number) {
  return request<VoteSessionResponse>('POST', `/api/tables/${tableId}/pause`)
}

export function requestUnpause(tableId: number) {
  return request<VoteSessionResponse>('POST', `/api/tables/${tableId}/unpause`)
}

export function requestKick(tableId: number, req: KickRequest) {
  return request<VoteSessionResponse>('POST', `/api/tables/${tableId}/kick`, req)
}

export function updateSettings(tableId: number, req: SettingsRequest) {
  return request<StatusMessage>('PATCH', `/api/tables/${tableId}/settings`, req)
}

export async function renameTable(tableId: number, name: string) {
  const res = await updateSettings(tableId, { name })
  // Known effect: this table's label changed — let the my-tables listing reflect it.
  emitSessionEvent({ type: 'renamed', tableId, name: name.trim() })
  return res
}

export function createVoteSession(tableId: number, req: CreateVoteSessionRequest) {
  return request<VoteSessionResponse>('POST', `/api/tables/${tableId}/voting-sessions`, req)
}

export function castVote(tableId: number, sessionId: string, req: CastVoteRequest) {
  return request<VoteSessionResponse>('PUT', `/api/tables/${tableId}/voting-sessions/${sessionId}/vote`, req)
}

export function listVoteSessions(tableId: number) {
  return request<VoteSummary[]>('GET', `/api/tables/${tableId}/voting-sessions`)
}
