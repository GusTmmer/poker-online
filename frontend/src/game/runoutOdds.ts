import type { RunoutOdds } from '../api/types'

/** A player's win probability as shown beside their tabled cards. */
export interface SeatEquity {
  /** 0..1 */
  value: number
  /** Ahead of (or level with) everyone else on the board shown. */
  leading: boolean
}

/** Each contender's odds with [boardCount] community cards showing; null when the frame has none for it. */
export function oddsAt(odds: RunoutOdds[], boardCount: number): Map<number, SeatEquity> | null {
  const street = odds.find((o) => o.boardCards === boardCount)
  if (!street || street.equities.length === 0) return null
  const best = Math.max(...street.equities.map((e) => e.equity))
  return new Map(street.equities.map((e) => [e.playerId, { value: e.equity, leading: e.equity === best }]))
}

/**
 * Whole percent, the way broadcasts show it — except that a hand that can still win or lose never
 * rounds to a certainty: it reads "<1%" or ">99%" instead of 0% or 100%.
 */
export function formatEquity(equity: number): string {
  if (equity <= 0) return '0%'
  if (equity >= 1) return '100%'
  const pct = Math.round(equity * 100)
  if (pct < 1) return '<1%'
  if (pct > 99) return '>99%'
  return `${pct}%`
}
