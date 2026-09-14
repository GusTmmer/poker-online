import { describe, expect, it } from 'vitest'
import type { RunoutOdds } from '../api/types'
import { formatEquity, oddsAt } from './runoutOdds'

const odds: RunoutOdds[] = [
  { boardCards: 0, equities: [{ playerId: 1, equity: 0.82 }, { playerId: 2, equity: 0.18 }] },
  { boardCards: 3, equities: [{ playerId: 1, equity: 0.5 }, { playerId: 2, equity: 0.5 }] },
  { boardCards: 5, equities: [{ playerId: 1, equity: 0 }, { playerId: 2, equity: 1 }] },
]

describe('oddsAt', () => {
  it('picks the odds for the board currently shown and marks the leader', () => {
    expect(oddsAt(odds, 0)).toEqual(new Map([
      [1, { value: 0.82, leading: true }],
      [2, { value: 0.18, leading: false }],
    ]))
    expect(oddsAt(odds, 5)?.get(2)).toEqual({ value: 1, leading: true })
  })

  it('treats a dead heat as both leading', () => {
    expect([...oddsAt(odds, 3)!.values()].every((e) => e.leading)).toBe(true)
  })

  it('is null for a board it has no odds for, or no odds at all', () => {
    expect(oddsAt(odds, 4)).toBeNull()
    expect(oddsAt([], 0)).toBeNull()
  })
})

describe('formatEquity', () => {
  it.each([
    [0, '0%'],
    [1, '100%'],
    [0.5, '50%'],
    [0.815, '82%'],
    [0.004, '<1%'],
    [0.996, '>99%'],
    [0.01, '1%'],
    [0.99, '99%'],
  ])('formats %f as %s', (equity, expected) => {
    expect(formatEquity(equity)).toBe(expected)
  })
})
