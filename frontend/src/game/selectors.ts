import type { GameStateUpdate } from '../api/types'

/**
 * What the action bar should offer the local player, derived purely from the
 * latest snapshot. The mode encodes the (order-sensitive) branch; the numeric
 * fields drive the raise slider / call button when `mode === 'acting'`.
 *
 * Pure and snapshot-only — this is steady-state UI, the counterpart to the
 * event-driven canvas effects.
 */
export type ControlMode = 'spectating' | 'idle' | 'lobby' | 'acting'

export interface Controls {
  mode: ControlMode
  isMyTurn: boolean
  amIReady: boolean
  /** Lobby only: one player left standing → offer a fresh game instead of a round. */
  isGameOver: boolean
  /** Acting only: chips needed to call (0 = check). */
  amountToCall: number
  /** Acting only: minimum legal raise-on-top (when above maxRaiseOnTop, only all-in is possible). */
  minRaise: number
  /** Acting only: largest raise-on-top (i.e. all-in size). */
  maxRaiseOnTop: number
  /** Acting only: whether a raise is possible at all. */
  canRaise: boolean
}

export function selectControls(state: GameStateUpdate, myPlayerId: number): Controls {
  const me = state.players.find((p) => p.id === myPlayerId)
  const roundInProgress = state.roundStage != null
  const isMyTurn = roundInProgress && state.nextPlayerIdToAct === myPlayerId
  const amIReady = state.readyPlayerIds.includes(myPlayerId)

  const myChips = me?.chips ?? 0
  const { amountToCall, minRaise, maxRaiseOnTop, canRaise } = bettingLimits(state, myChips, me?.currentBet ?? 0)

  const isGameOver =
    state.gameStatus === 'WAITING' && state.players.filter((p) => p.chips > 0).length <= 1

  // Branch precedence matters: a 0-chip player mid-hand is spectating even if
  // also IDLE; IDLE outside a hand still can't ready/start.
  const mode: ControlMode =
    roundInProgress && myChips === 0 ? 'spectating'
    : me?.status === 'IDLE' ? 'idle'
    : !roundInProgress ? 'lobby'
    : 'acting'

  return { mode, isMyTurn, amIReady, isGameOver, amountToCall, minRaise, maxRaiseOnTop, canRaise }
}

/**
 * The server computes the betting limits (it knows the last raise size and whether raising is reopened),
 * so use them when present. The fallback approximates them from the snapshot for frames without them.
 */
function bettingLimits(state: GameStateUpdate, myChips: number, myCurrentBet: number) {
  const options = state.myBettingOptions
  if (options) {
    return {
      amountToCall: options.amountToCall,
      minRaise: options.minRaiseBy,
      maxRaiseOnTop: options.maxRaiseBy,
      canRaise: options.canRaise,
    }
  }
  const maxCurrentBet = Math.max(0, ...state.players.map((p) => p.currentBet))
  const amountToCall = Math.max(0, maxCurrentBet - myCurrentBet)
  const maxRaiseOnTop = Math.max(0, myChips - amountToCall)
  return {
    amountToCall,
    minRaise: state.blinds.big,
    maxRaiseOnTop,
    canRaise: maxRaiseOnTop > 0,
  }
}
