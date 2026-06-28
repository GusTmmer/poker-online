import {
  ApiError,
  type ActionRequest,
  type CastVoteRequest,
  type CreateTableRequest,
  type CreateTableResponse,
  type CreateVoteSessionRequest,
  type JoinRequest,
  type JoinResponse,
  type KickRequest,
  type SettingsRequest,
  type StatusMessage,
  type TableInfoResponse,
  type VoteSessionResponse,
  type VoteSummary,
} from './types'

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

export function createTable(req: CreateTableRequest) {
  return request<CreateTableResponse>('POST', '/api/tables', req)
}

export function getTableInfo(tableId: number) {
  return request<TableInfoResponse>('GET', `/api/tables/${tableId}`)
}

export function joinTable(tableId: number, req: JoinRequest) {
  return request<JoinResponse>('POST', `/api/tables/${tableId}/players`, req)
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

export function createVoteSession(tableId: number, req: CreateVoteSessionRequest) {
  return request<VoteSessionResponse>('POST', `/api/tables/${tableId}/voting-sessions`, req)
}

export function castVote(tableId: number, sessionId: string, req: CastVoteRequest) {
  return request<VoteSessionResponse>('PUT', `/api/tables/${tableId}/voting-sessions/${sessionId}/vote`, req)
}

export function listVoteSessions(tableId: number) {
  return request<VoteSummary[]>('GET', `/api/tables/${tableId}/voting-sessions`)
}
