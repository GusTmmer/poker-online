import type { GameStateUpdate } from '../api/types'
import type { BestHand, DeriveContext, GameEvent } from './events'

/**
 * Convert a pair of consecutive snapshots into the list of semantic events that
 * occurred between them. This is the *only* place the app diffs game state — all
 * transient effects flow from here.
 *
 * Pure except for the in-place bookkeeping it keeps on `ctx` (round-start chip
 * counts). `prev === null` (first frame after connect/reconnect) yields no
 * events: a cold snapshot is rendered statically, never replayed as animation.
 */
export function deriveEvents(
  prev: GameStateUpdate | null,
  next: GameStateUpdate,
  ctx: DeriveContext,
): GameEvent[] {
  if (prev === null) {
    // Cold start: seed round-start chips if we connected mid-hand so a later
    // showdown/award can still attribute winnings, but emit nothing to animate.
    if (next.gameStatus === 'RUNNING') ctx.roundStartChips = chipMap(next)
    return []
  }

  const events: GameEvent[] = []

  // Snapshot stacks at the start of each hand — winner detection compares
  // against this baseline.
  if (prev.gameStatus !== 'RUNNING' && next.gameStatus === 'RUNNING') {
    ctx.roundStartChips = chipMap(next)
  }

  // New hand → deal animation.
  if (prev.roundStage == null && next.roundStage === 'BET_BLINDS') {
    events.push({ kind: 'round_started' })
  }

  // Acting player changed.
  if (prev.nextPlayerIdToAct !== next.nextPlayerIdToAct) {
    events.push({ kind: 'turn_changed', playerId: next.nextPlayerIdToAct })
  }

  // Community cards added (flop/turn/river). Reset-to-empty is steady state,
  // not an event.
  if (next.communityCards.length > prev.communityCards.length) {
    events.push({
      kind: 'community_revealed',
      added: next.communityCards.slice(prev.communityCards.length),
      total: next.communityCards.length,
    })
  }

  // Per-player bet increases → flying chip; mid-hand stack changes → delta.
  const prevBets = new Map(prev.players.map((p) => [p.id, p.currentBet]))
  const prevChips = new Map(prev.players.map((p) => [p.id, p.chips]))
  for (const p of next.players) {
    if ((prevBets.get(p.id) ?? 0) < p.currentBet) {
      events.push({ kind: 'player_bet', playerId: p.id, amount: p.currentBet - (prevBets.get(p.id) ?? 0) })
    }
    const before = prevChips.get(p.id)
    // Winnings paid out as the hand ends (WAITING) are shown via winner chips,
    // not a floating delta — matching the original behaviour.
    if (before !== undefined && before !== p.chips && next.gameStatus !== 'WAITING') {
      events.push({ kind: 'chips_changed', playerId: p.id, delta: p.chips - before })
    }
  }

  // A player who was in the hand is no longer active while the hand continues →
  // they folded. Restricted to an ongoing betting round so it never fires on the
  // showdown/round-end reset (where everyone leaves the hand at once); a fold that
  // ends the hand is presented by the pot award instead.
  if (next.roundStage != null && next.roundStage !== 'SHOWDOWN') {
    const prevActive = new Map(prev.players.map((p) => [p.id, p.isActive]))
    for (const p of next.players) {
      if (prevActive.get(p.id) === true && !p.isActive) {
        events.push({ kind: 'player_folded', playerId: p.id })
      }
    }
  }

  if (prev.potTotal !== next.potTotal) {
    events.push({ kind: 'pot_changed', from: prev.potTotal, to: next.potTotal })
  }

  // Showdown — chips are already distributed in this broadcast.
  if (prev.roundStage !== 'SHOWDOWN' && next.roundStage === 'SHOWDOWN') {
    const hands: Record<number, BestHand> = {}
    for (const p of next.players) if (p.bestHand) hands[p.id] = p.bestHand
    events.push({ kind: 'showdown', hands, winnerIds: winners(next, ctx).map((w) => w.playerId) })
  }

  // Hand ended (everyone folded, or after showdown) → award the pot.
  if (prev.gameStatus === 'RUNNING' && next.gameStatus === 'WAITING') {
    events.push({ kind: 'pot_awarded', winners: winners(next, ctx) })
  }

  if (prev.gameStatus !== 'PAUSED' && next.gameStatus === 'PAUSED') {
    events.push({ kind: 'game_paused' })
  }
  if (prev.gameStatus === 'PAUSED' && next.gameStatus !== 'PAUSED') {
    events.push({ kind: 'game_resumed' })
  }

  const prevStatus = new Map(prev.players.map((p) => [p.id, p.status]))
  for (const p of next.players) {
    const was = prevStatus.get(p.id)
    if (was !== undefined && was !== p.status) {
      events.push({ kind: 'player_status_changed', playerId: p.id, status: p.status })
    }
  }

  return events
}

function chipMap(state: GameStateUpdate): Map<number, number> {
  return new Map(state.players.map((p) => [p.id, p.chips]))
}

/** Players whose stack exceeds their round-start baseline — i.e. won chips. */
function winners(state: GameStateUpdate, ctx: DeriveContext): { playerId: number; delta: number }[] {
  const out: { playerId: number; delta: number }[] = []
  for (const p of state.players) {
    const start = ctx.roundStartChips.get(p.id) ?? p.chips
    if (p.chips > start) out.push({ playerId: p.id, delta: p.chips - start })
  }
  return out
}
