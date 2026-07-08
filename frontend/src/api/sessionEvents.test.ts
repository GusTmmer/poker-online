import { describe, expect, it, vi } from 'vitest'
import { emitSessionEvent, subscribeToSessionEvents, type SessionEvent } from './sessionEvents'

describe('sessionEvents', () => {
  it('delivers an emitted event to a subscriber', () => {
    const seen: SessionEvent[] = []
    const unsubscribe = subscribeToSessionEvents((e) => seen.push(e))
    emitSessionEvent({ type: 'left', tableId: 7 })
    unsubscribe()
    expect(seen).toEqual([{ type: 'left', tableId: 7 }])
  })

  it('stops delivering after unsubscribe', () => {
    const listener = vi.fn()
    const unsubscribe = subscribeToSessionEvents(listener)
    unsubscribe()
    emitSessionEvent({ type: 'left', tableId: 1 })
    expect(listener).not.toHaveBeenCalled()
  })

  it('fans out to every subscriber', () => {
    const a = vi.fn()
    const b = vi.fn()
    const unsubA = subscribeToSessionEvents(a)
    const unsubB = subscribeToSessionEvents(b)
    emitSessionEvent({ type: 'renamed', tableId: 2, name: 'X' })
    unsubA()
    unsubB()
    expect(a).toHaveBeenCalledOnce()
    expect(b).toHaveBeenCalledOnce()
  })

  it('is a no-op when there are no subscribers', () => {
    expect(() => emitSessionEvent({ type: 'left', tableId: 9 })).not.toThrow()
  })
})
