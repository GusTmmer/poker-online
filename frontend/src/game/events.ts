import type { PlayerStatus, PlayerView } from '../api/types'

/** Non-null best-hand shape, reused from the wire type. */
export type BestHand = NonNullable<PlayerView['bestHand']>

/**
 * Semantic facts about *what just happened* between two consecutive game-state
 * snapshots. These are the single source of truth for transient effects
 * (animations, sounds, toasts) — anything that fires once rather than reflecting
 * steady-state. Steady-state ("what things are": chip counts, badges, halos,
 * the pot amount) is rendered directly from the latest snapshot instead.
 *
 * Today these are derived on the frontend by `deriveEvents`. The contract is
 * deliberately backend-agnostic: if the server later emits these events
 * directly, every consumer keeps working unchanged.
 */
export type GameEvent =
  /** A new hand began — triggers the dealing animation and resets win state. */
  | { kind: 'round_started' }
  /** Community cards were added (flop/turn/river) — triggers slide + flip. */
  | { kind: 'community_revealed'; added: string[]; total: number }
  /** A player's committed bet grew — triggers a chip flying to the pot. */
  | { kind: 'player_bet'; playerId: number; amount: number }
  /** A player folded mid-hand — triggers their cards flying to the muck (center). */
  | { kind: 'player_folded'; playerId: number }
  /** The acting player changed (or became none). */
  | { kind: 'turn_changed'; playerId: number | null }
  /** The pot total changed. */
  | { kind: 'pot_changed'; from: number; to: number }
  /** A player's stack changed mid-hand — triggers a floating +/- delta. */
  | { kind: 'chips_changed'; playerId: number; delta: number }
  /** A player's connection/seat status changed. */
  | { kind: 'player_status_changed'; playerId: number; status: PlayerStatus }
  /** Reached showdown — best hands are known and winners decided. */
  | { kind: 'showdown'; hands: Record<number, BestHand>; winnerIds: number[] }
  /** The hand ended and the pot was awarded — triggers winner chips. */
  | { kind: 'pot_awarded'; winners: { playerId: number; delta: number }[] }
  | { kind: 'game_paused' }
  | { kind: 'game_resumed' }

/**
 * Cross-frame bookkeeping that `deriveEvents` needs but a single snapshot can't
 * provide — notably each player's stack at the moment the hand started, used to
 * decide who won. Owned by the pipeline (one instance per table connection) and
 * mutated in place by `deriveEvents`.
 */
export interface DeriveContext {
  /** playerId -> chip count captured when the current hand began. */
  roundStartChips: Map<number, number>
}

export function createDeriveContext(): DeriveContext {
  return { roundStartChips: new Map() }
}
