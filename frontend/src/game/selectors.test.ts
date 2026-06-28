import { describe, expect, it } from 'vitest'
import type { GameStateUpdate, PlayerView } from '../api/types'
import { selectControls } from './selectors'

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

describe('selectControls', () => {
  it('is lobby when no round is in progress', () => {
    const c = selectControls(state({ readyPlayerIds: [1] }), 1)
    expect(c.mode).toBe('lobby')
    expect(c.amIReady).toBe(true)
    expect(c.isGameOver).toBe(false)
  })

  it('flags game over when one player has chips left', () => {
    const s = state({ players: [player({ id: 1, chips: 2000 }), player({ id: 2, chips: 0 })] })
    expect(selectControls(s, 1).isGameOver).toBe(true)
  })

  it('is acting on the local player turn with call/raise amounts', () => {
    const s = state({
      roundStage: 'BET_FLOP',
      nextPlayerIdToAct: 1,
      players: [player({ id: 1, chips: 1000, currentBet: 20 }), player({ id: 2, currentBet: 100 })],
    })
    const c = selectControls(s, 1)
    expect(c.mode).toBe('acting')
    expect(c.isMyTurn).toBe(true)
    expect(c.amountToCall).toBe(80)       // 100 - 20
    expect(c.minRaise).toBe(100)          // max(bigBlind 20, maxBet 100)
    expect(c.maxRaiseOnTop).toBe(920)     // 1000 - 80
    expect(c.canRaise).toBe(true)
  })

  it('is acting but not my turn when another player acts', () => {
    const s = state({ roundStage: 'BET_FLOP', nextPlayerIdToAct: 2 })
    expect(selectControls(s, 1).isMyTurn).toBe(false)
  })

  it('spectates a 0-chip player mid-hand, even before idle is considered', () => {
    const s = state({
      roundStage: 'BET_FLOP',
      players: [player({ id: 1, chips: 0, status: 'IDLE' }), player({ id: 2 })],
    })
    expect(selectControls(s, 1).mode).toBe('spectating')
  })

  it('is idle when the player went idle outside a hand they can act in', () => {
    const s = state({ roundStage: 'BET_FLOP', players: [player({ id: 1, status: 'IDLE', chips: 500 }), player({ id: 2 })] })
    expect(selectControls(s, 1).mode).toBe('idle')
  })

  it('cannot raise when the stack only covers the call', () => {
    const s = state({
      roundStage: 'BET_FLOP',
      nextPlayerIdToAct: 1,
      players: [player({ id: 1, chips: 50, currentBet: 0 }), player({ id: 2, currentBet: 100 })],
    })
    const c = selectControls(s, 1)
    expect(c.amountToCall).toBe(100)
    expect(c.maxRaiseOnTop).toBe(0)
    expect(c.canRaise).toBe(false)
  })
})
