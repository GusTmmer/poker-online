export interface CreateTableRequest {
  playerName: string
  name?: string
  startingChips?: number
  turnTimerSeconds?: number
  maxPlayers?: number
  blindEscalationOrbits?: number
  blindEscalationMultiplier?: number
}

export interface CreateTableResponse {
  tableId: number
  joinLink: string
  playerId: number
}

export interface JoinRequest {
  playerName: string
}

export interface TableSummary {
  tableId: number
  name: string
  playerName: string
  gameStatus: GameStatus
  playerCount: number
  maxPlayers: number
}

export interface MyTablesResponse {
  tables: TableSummary[]
}

export interface JoinResponse {
  playerId: number
  playerName: string
}

export type GameStatus = 'WAITING' | 'RUNNING' | 'PAUSED'
export type PlayerStatus = 'ONLINE' | 'OFFLINE' | 'IDLE' | 'ELIMINATED'

export interface PlayerInfo {
  id: number
  name: string
  status: PlayerStatus
  chips: number
}

export interface TableInfoResponse {
  tableId: number
  name: string
  players: PlayerInfo[]
  isOpen: boolean
  maxPlayers: number
  sessionPlayerId: number | null
  hasSession: boolean
  gameStatus: GameStatus
}

export type ActionType = 'FOLD' | 'CALL' | 'RAISE' | 'ALL_IN'

export interface ActionRequest {
  type: ActionType
  value?: number
}

export interface KickRequest {
  targetPlayerId: number
}

export interface SettingsRequest {
  isOpen?: boolean
  name?: string
}

export type StatusMessage = { status: string; sessionId?: string }

export type VoteResolutionType =
  | 'PAUSE_GAME'
  | 'UNPAUSE_GAME'
  | 'KICK_PLAYER'
  | 'RESTART_GAME'
  | 'INCREASE_BLINDS'

export type VoteOutcome = 'PASSED' | 'FAILED' | 'PENDING'

export interface CreateVoteSessionRequest {
  resolution: VoteResolutionType
  targetPlayerId?: number
}

export interface CastVoteRequest {
  vote: 'yes' | 'no'
}

export interface VoteSessionResponse {
  sessionId: string
  resolutionType: VoteResolutionType
  targetPlayerId: number | null
  yesCount: number
  noCount: number
  requiredVotes: number
  outcome: VoteOutcome
}

export interface VoteSummary {
  sessionId: string
  resolutionType: VoteResolutionType
  targetPlayerId: number | null
  yesCount: number
  noCount: number
  requiredVotes: number
}

export type PokerRoundStage =
  | 'INIT'
  | 'BET_BLINDS'
  | 'BET_FLOP'
  | 'BET_TURN'
  | 'BET_RIVER'
  | 'SHOWDOWN'

export interface PlayerView {
  id: number
  name: string
  chips: number
  status: PlayerStatus
  isActive: boolean
  currentBet: number
  pocketCards: string[] | null
  isDealer: boolean
  isSmallBlind: boolean
  isBigBlind: boolean
  bestHand: { name: string; cards: string[] } | null
}

export interface BlindInfo {
  big: number
  small: number
}

export interface GameStateUpdate {
  type: 'game_state'
  tableId: number
  players: PlayerView[]
  communityCards: string[]
  potTotal: number
  nextPlayerIdToAct: number | null
  roundStage: PokerRoundStage | null
  blinds: BlindInfo
  gameStatus: GameStatus
  readyPlayerIds: number[]
  activeVotes: VoteSummary[]
  message: string | null
  /** Epoch-ms when the current turn timer expires. Absent when no timer is running. */
  turnTimerEndsAt?: number
}

export interface SimpleMessage {
  type: string
  message: string
}

export type WsFrame = GameStateUpdate | SimpleMessage

export function isGameStateUpdate(frame: WsFrame): frame is GameStateUpdate {
  return frame.type === 'game_state'
}

// kotlinx.serialization's default Json encoder (encodeDefaults = false) omits any field
// that equals its declared default - empty lists, null, and false booleans vanish from the
// wire entirely instead of being sent explicitly. Normalize those back in so the rest of the
// app can trust the types above.
export function normalizeGameState(raw: GameStateUpdate): GameStateUpdate {
  return {
    ...raw,
    readyPlayerIds: raw.readyPlayerIds ?? [],
    activeVotes: raw.activeVotes ?? [],
    message: raw.message ?? null,
    players: raw.players.map((p) => ({
      ...p,
      isDealer: p.isDealer ?? false,
      isSmallBlind: p.isSmallBlind ?? false,
      isBigBlind: p.isBigBlind ?? false,
      bestHand: p.bestHand ?? null,
    })),
  }
}

export class ApiError extends Error {
  status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}
