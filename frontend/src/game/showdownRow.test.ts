import { describe, expect, it } from 'vitest'
import { showdownRow, type RowCard } from './showdownRow'

const cards = (row: RowCard[]) => row.map((c) => c.card)
const ringed = (row: RowCard[]) => row.filter((c) => c.ringed).map((c) => c.card)
const dimmed = (row: RowCard[]) => row.filter((c) => c.dimmed).map((c) => c.card)

describe('showdownRow', () => {
  it('shows just the five, in the hand’s order, when both pocket cards play', () => {
    const row = showdownRow({ name: 'Full House', cards: ['JH', 'JS', 'JD', '3D', '3H'] }, ['JS', 'JH'])
    expect(cards(row.hand)).toEqual(['JH', 'JS', 'JD', '3D', '3H'])
    expect(ringed(row.hand)).toEqual(['JH', 'JS'])
    expect(row.aside).toEqual([])
  })

  it('adds the pocket pair when only one of them plays: 7 cards, the unused one dimmed', () => {
    const row = showdownRow({ name: 'One Pair', cards: ['3D', '3H', 'AH', 'JD', '8D'] }, ['AH', '5C'])
    expect(ringed(row.hand)).toEqual(['AH'])
    expect(cards(row.aside)).toEqual(['AH', '5C'])
    expect(ringed(row.aside)).toEqual(['AH'])
    expect(dimmed(row.aside)).toEqual(['5C'])
    expect(row.hand.length + row.aside.length).toBe(7)
  })

  it('adds the pocket pair, both dimmed, when the board plays', () => {
    const row = showdownRow({ name: 'Straight', cards: ['9D', '8C', '7C', '6C', '5H'] }, ['2S', 'KD'])
    expect(ringed(row.hand)).toEqual([])
    expect(dimmed(row.aside)).toEqual(['2S', 'KD'])
  })

  it('shows only the pair, undimmed, while an all-in board is still running out', () => {
    const row = showdownRow(null, ['AS', 'KS'])
    expect(row.hand).toEqual([])
    expect(row.aside).toEqual([
      { card: 'AS', ringed: false, dimmed: false },
      { card: 'KS', ringed: false, dimmed: false },
    ])
  })

  it('shows only the five when the pocket cards are unknown', () => {
    const row = showdownRow({ name: 'Flush', cards: ['AH', 'KH', 'QH', 'JH', '9H'] }, [])
    expect(cards(row.hand)).toHaveLength(5)
    expect(row.aside).toEqual([])
  })
})
