import { describe, expect, it } from 'vitest'
import { raiseStep, snapRaiseValue } from './snapRaiseValue'

describe('snapRaiseValue', () => {
  it('keeps 0 as 0 (call/check)', () => {
    expect(snapRaiseValue(0, 20, 920, 10)).toBe(0)
  })

  it('snaps values below the minimum raise up to it', () => {
    expect(snapRaiseValue(10, 20, 920, 10)).toBe(20)
  })

  it('passes legal in-between values through unchanged', () => {
    expect(snapRaiseValue(500, 20, 920, 10)).toBe(500)
    expect(snapRaiseValue(20, 20, 920, 10)).toBe(20)
    expect(snapRaiseValue(35, 20, 920, 5)).toBe(35)
  })

  it('snaps the top step to all-in when the max is not a step multiple', () => {
    // startingChips 300, blinds 3/6, amountToCall 6 → maxRaiseOnTop 294; the
    // browser caps drags/End at 290, which must still mean all-in.
    expect(snapRaiseValue(290, 6, 294, 10)).toBe(294)
  })

  it('leaves the last full step alone when the max itself is reachable', () => {
    expect(snapRaiseValue(290, 6, 300, 10)).toBe(290)
    expect(snapRaiseValue(300, 6, 300, 10)).toBe(300)
  })

  it('treats any positive value as all-in when the stack cannot cover a minimum raise', () => {
    expect(snapRaiseValue(8, 6, 8, 10)).toBe(8)
    expect(snapRaiseValue(10, 40, 30, 10)).toBe(30)
  })
})

describe('raiseStep', () => {
  it('follows the small blind, never below one chip', () => {
    expect(raiseStep(15)).toBe(15)
    expect(raiseStep(0)).toBe(1)
  })
})
