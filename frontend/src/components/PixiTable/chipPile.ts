import { Container, Graphics } from 'pixi.js'
import type { GameStateUpdate } from '../../api/types'
import { CW, drawChipStacks, fillPotGroups, type PotGroup } from './pot'

// ─── seat chip piles ──────────────────────────────────────────────────────────
// Each player's stack as real clay chips on the felt in front of them, so a big stack
// looks big. Sized against the table's average stack (total chips in play ÷ seats,
// i.e. effectively the starting stack), not the blinds — piles don't all shrink the
// moment the blinds go up.

const PILE_SCALE = 0.72
const CHIPS_AT_AVERAGE = 8
const MAX_PILE_CHIPS = 24

// The centre stack fills first, then one to its left, then its right.
const PILE_GROUPS: PotGroup[] = [
  { max: 8, positions: [{ cx: 0, colorOff: 0, raise: 0 }] },
  { max: 8, positions: [{ cx: -CW * 0.95, colorOff: 3, raise: 2 }] },
  { max: 8, positions: [{ cx: CW * 0.95, colorOff: 1, raise: 2 }] },
]

/** The average stack at the table — the unit piles are measured in. */
export function pileUnit(state: GameStateUpdate): number {
  const total = state.players.reduce((sum, p) => sum + p.chips, 0) + state.potTotal
  return total / Math.max(1, state.players.length)
}

export function pileChipCount(chips: number, unit: number): number {
  if (chips <= 0 || unit <= 0) return 0
  return Math.max(1, Math.min(MAX_PILE_CHIPS, Math.round(CHIPS_AT_AVERAGE * Math.sqrt(chips / unit))))
}

export function makePile(): Container {
  const c = new Container()
  c.scale.set(PILE_SCALE)
  return c
}

/** Redraws [pile] with [count] chips. */
export function drawPile(pile: Container, count: number) {
  pile.removeChildren()
  if (count <= 0) return
  const g = new Graphics()
  drawChipStacks(g, fillPotGroups(count, PILE_GROUPS))
  pile.addChild(g)
}
