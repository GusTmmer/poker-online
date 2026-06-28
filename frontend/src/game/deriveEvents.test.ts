import { describe, expect, it } from 'vitest'
import type { GameStateUpdate, PlayerView } from '../api/types'
import { deriveEvents } from './deriveEvents'
import { createDeriveContext, type GameEvent } from './events'

function player(over: Partial<PlayerView> & { id: number }): PlayerView {
  return {
    name: `P${over.id}`,
    chips: 1000,
    status: 'ONLINE',
    isActive: true,
    currentBet: 0,
    pocketCards: null,
    isDealer: false,
    isSmallBlind: false,
    isBigBlind: false,
    bestHand: null,
    ...over,
  }
}

function state(over: Partial<GameStateUpdate> = {}): GameStateUpdate {
  return {
    type: 'game_state',
    tableId: 1,
    players: [player({ id: 1 }), player({ id: 2 })],
    communityCards: [],
    potTotal: 0,
    nextPlayerIdToAct: null,
    roundStage: null,
    blinds: { big: 20, small: 10 },
    gameStatus: 'WAITING',
    readyPlayerIds: [],
    activeVotes: [],
    message: null,
    ...over,
  }
}

const kinds = (events: GameEvent[]) => events.map((e) => e.kind)

describe('deriveEvents', () => {
  it('emits nothing on a cold start (prev = null)', () => {
    expect(deriveEvents(null, state(), createDeriveContext())).toEqual([])
  })

  it('seeds round-start chips on cold start mid-hand so winners are attributable', () => {
    const ctx = createDeriveContext()
    deriveEvents(null, state({ gameStatus: 'RUNNING', roundStage: 'BET_FLOP' }), ctx)
    expect(ctx.roundStartChips.get(1)).toBe(1000)
  })

  it('emits round_started when a hand begins', () => {
    const prev = state({ gameStatus: 'RUNNING', roundStage: null })
    const next = state({ gameStatus: 'RUNNING', roundStage: 'BET_BLINDS' })
    expect(kinds(deriveEvents(prev, next, createDeriveContext()))).toContain('round_started')
  })

  it('emits turn_changed when the acting player changes', () => {
    const prev = state({ roundStage: 'BET_FLOP', nextPlayerIdToAct: 1 })
    const next = state({ roundStage: 'BET_FLOP', nextPlayerIdToAct: 2 })
    const evt = deriveEvents(prev, next, createDeriveContext()).find((e) => e.kind === 'turn_changed')
    expect(evt).toEqual({ kind: 'turn_changed', playerId: 2 })
  })

  it('emits community_revealed only for newly added cards', () => {
    const prev = state({ communityCards: ['AH', 'KD', 'QS'] })
    const next = state({ communityCards: ['AH', 'KD', 'QS', 'JC'] })
    const evt = deriveEvents(prev, next, createDeriveContext()).find((e) => e.kind === 'community_revealed')
    expect(evt).toEqual({ kind: 'community_revealed', added: ['JC'], total: 4 })
  })

  it('does not emit community_revealed when cards reset to empty', () => {
    const prev = state({ communityCards: ['AH', 'KD', 'QS', 'JC', 'TD'] })
    const next = state({ communityCards: [] })
    expect(kinds(deriveEvents(prev, next, createDeriveContext()))).not.toContain('community_revealed')
  })

  it('emits player_bet with the increase amount', () => {
    const prev = state({ players: [player({ id: 1, currentBet: 20 }), player({ id: 2 })] })
    const next = state({ players: [player({ id: 1, currentBet: 60 }), player({ id: 2 })] })
    const evt = deriveEvents(prev, next, createDeriveContext()).find((e) => e.kind === 'player_bet')
    expect(evt).toEqual({ kind: 'player_bet', playerId: 1, amount: 40 })
  })

  it('emits chips_changed mid-hand but not as the hand ends', () => {
    const ctx = createDeriveContext()
    // mid-hand (RUNNING): delta shown
    const midPrev = state({ gameStatus: 'RUNNING', roundStage: 'SHOWDOWN', players: [player({ id: 1, chips: 1000 }), player({ id: 2 })] })
    const midNext = state({ gameStatus: 'RUNNING', roundStage: 'SHOWDOWN', players: [player({ id: 1, chips: 800 }), player({ id: 2 })] })
    expect(kinds(deriveEvents(midPrev, midNext, ctx))).toContain('chips_changed')

    // hand ending (-> WAITING): no floating delta, winner chips instead
    const endPrev = state({ gameStatus: 'RUNNING', players: [player({ id: 1, chips: 1000 }), player({ id: 2 })] })
    const endNext = state({ gameStatus: 'WAITING', players: [player({ id: 1, chips: 1200 }), player({ id: 2, chips: 800 })] })
    expect(kinds(deriveEvents(endPrev, endNext, ctx))).not.toContain('chips_changed')
  })

  it('detects winners at showdown relative to round-start chips', () => {
    const ctx = createDeriveContext()
    // round starts -> baseline captured
    deriveEvents(
      state({ gameStatus: 'WAITING' }),
      state({ gameStatus: 'RUNNING', roundStage: 'BET_BLINDS' }),
      ctx,
    )
    const prev = state({ gameStatus: 'RUNNING', roundStage: 'BET_RIVER' })
    const next = state({
      gameStatus: 'RUNNING',
      roundStage: 'SHOWDOWN',
      players: [
        player({ id: 1, chips: 1300, bestHand: { name: 'Flush', cards: ['AH', 'KH', 'QH', 'JH', '9H'] } }),
        player({ id: 2, chips: 700 }),
      ],
    })
    const evt = deriveEvents(prev, next, ctx).find((e) => e.kind === 'showdown')
    expect(evt).toMatchObject({ kind: 'showdown', winnerIds: [1] })
    if (evt?.kind === 'showdown') expect(evt.hands[1]?.name).toBe('Flush')
  })

  it('emits pot_awarded with winner deltas when the hand ends', () => {
    const ctx = createDeriveContext()
    deriveEvents(state({ gameStatus: 'WAITING' }), state({ gameStatus: 'RUNNING', roundStage: 'BET_BLINDS' }), ctx)
    const prev = state({ gameStatus: 'RUNNING', roundStage: 'SHOWDOWN' })
    const next = state({
      gameStatus: 'WAITING',
      players: [player({ id: 1, chips: 1200 }), player({ id: 2, chips: 800 })],
    })
    const evt = deriveEvents(prev, next, ctx).find((e) => e.kind === 'pot_awarded')
    expect(evt).toEqual({ kind: 'pot_awarded', winners: [{ playerId: 1, delta: 200 }] })
  })

  it('emits pause/resume transitions', () => {
    const running = state({ gameStatus: 'RUNNING' })
    const paused = state({ gameStatus: 'PAUSED' })
    expect(kinds(deriveEvents(running, paused, createDeriveContext()))).toContain('game_paused')
    expect(kinds(deriveEvents(paused, running, createDeriveContext()))).toContain('game_resumed')
  })

  it('emits player_status_changed', () => {
    const prev = state({ players: [player({ id: 1, status: 'ONLINE' }), player({ id: 2 })] })
    const next = state({ players: [player({ id: 1, status: 'OFFLINE' }), player({ id: 2 })] })
    const evt = deriveEvents(prev, next, createDeriveContext()).find((e) => e.kind === 'player_status_changed')
    expect(evt).toEqual({ kind: 'player_status_changed', playerId: 1, status: 'OFFLINE' })
  })
})
