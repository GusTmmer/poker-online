import type { BestHand } from './events'

/** One card in a seat's showdown row. */
export interface RowCard {
  card: string
  /** A pocket card that plays in the hand — drawn with the gold ring. */
  ringed: boolean
  /** A pocket card the hand doesn't use. */
  dimmed: boolean
}

/** A seat's showdown row: the hand's five cards, then — after a divider — the pocket pair when it's needed. */
export interface ShowdownRow {
  hand: RowCard[]
  aside: RowCard[]
}

/**
 * What a seat shows at showdown. The hand's five cards keep the server's order — highest first by the
 * hand's structure (the trips, then the kickers; a wheel as 5-4-3-2-A) — with the pocket cards that play
 * ringed. When the five don't use both pocket cards, the pair follows as well, so the player's own cards
 * are always visible: 7 cards, the pocket card that plays ringed again and the other dimmed.
 *
 * With no hand yet (pocket cards tabled during an all-in runout) only the pair is shown; with no pocket
 * cards known, only the five.
 */
export function showdownRow(hand: BestHand | null, pocket: string[]): ShowdownRow {
  const inHand = new Set(hand?.cards ?? [])
  const handRow = (hand?.cards ?? []).map((card) => ({ card, ringed: pocket.includes(card), dimmed: false }))
  const showPocket = hand == null || pocket.some((c) => !inHand.has(c))
  const aside = showPocket
    ? pocket.map((card) => ({ card, ringed: inHand.has(card), dimmed: hand != null && !inHand.has(card) }))
    : []
  return { hand: handRow, aside }
}
