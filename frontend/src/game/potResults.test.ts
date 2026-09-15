import { describe, expect, it } from 'vitest'
import type { PlayerView, PotView } from '../api/types'
import { potResultLines, potWinnerIds } from './potResults'

const players = [
  { id: 0, name: 'Gus' },
  { id: 1, name: 'Ada' },
  { id: 2, name: 'Bishop' },
] as PlayerView[]

const pot = (over: Partial<PotView>): PotView => ({ amount: 100, contenderIds: [0, 1], winnerIds: [], reason: null, ...over })

describe('potResultLines', () => {
  it('is empty before the showdown decides anything', () => {
    expect(potResultLines([pot({})], players)).toEqual([])
  })

  it('names the winner and what decided a single pot', () => {
    expect(potResultLines([pot({ winnerIds: [1], reason: 'Queen kicker' })], players)).toEqual(['Ada wins — Queen kicker'])
    expect(potResultLines([pot({ winnerIds: [2] })], players)).toEqual(['Bishop wins'])
  })

  it('shows a split as a split, not as a win', () => {
    expect(potResultLines([pot({ winnerIds: [0, 1], reason: 'Split pot' })], players)).toEqual(['Split — Gus & Ada'])
    expect(potResultLines([pot({ winnerIds: [0, 1, 2], reason: 'Split pot' })], players)).toEqual(['Split — Gus, Ada & Bishop'])
  })

  it('shows one line when the same player scoops every pot the same way', () => {
    const pots = [pot({ winnerIds: [1], reason: 'Jack kicker' }), pot({ winnerIds: [1], reason: 'Jack kicker' })]
    expect(potResultLines(pots, players)).toEqual(['Ada wins — Jack kicker'])
  })

  it('labels each pot when there are side pots', () => {
    const pots = [pot({ winnerIds: [2] }), pot({ winnerIds: [1], reason: 'King kicker' })]
    expect(potResultLines(pots, players)).toEqual(['Main pot: Bishop wins', 'Side pot: Ada wins — King kicker'])
  })
})

describe('potWinnerIds', () => {
  it('collects every pot winner once', () => {
    expect(potWinnerIds([pot({ winnerIds: [2] }), pot({ winnerIds: [2, 1] })])).toEqual([2, 1])
  })
})
