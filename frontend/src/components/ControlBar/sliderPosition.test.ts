import { describe, expect, it } from 'vitest'
import { sliderFraction, sliderValueAt } from './sliderPosition'

describe('sliderFraction', () => {
  const rect = { left: 100, top: 50, width: 218, height: 20 }

  it('measures along x between the thumb centres', () => {
    expect(sliderFraction({ x: 109, y: 60 }, rect, false, 18)).toBe(0)
    expect(sliderFraction({ x: 209, y: 60 }, rect, false, 18)).toBe(0.5)
    expect(sliderFraction({ x: 309, y: 60 }, rect, false, 18)).toBe(1)
  })

  it('clamps outside the track', () => {
    expect(sliderFraction({ x: 0, y: 60 }, rect, false, 18)).toBe(0)
    expect(sliderFraction({ x: 900, y: 60 }, rect, false, 18)).toBe(1)
  })

  it('measures down the screen on a stage rotated 90° clockwise', () => {
    // The same slider turned: its box is tall and its start is at the top.
    const turned = { left: 300, top: 100, width: 20, height: 218 }
    expect(sliderFraction({ x: 310, y: 109 }, turned, true, 18)).toBe(0)
    expect(sliderFraction({ x: 310, y: 209 }, turned, true, 18)).toBe(0.5)
    // Sideways finger movement on the screen doesn't move it.
    expect(sliderFraction({ x: 900, y: 209 }, turned, true, 18)).toBe(0.5)
  })

  it('is 0 for a track no longer than the thumb', () => {
    expect(sliderFraction({ x: 105, y: 60 }, { ...rect, width: 18 }, false, 18)).toBe(0)
  })
})

describe('sliderValueAt', () => {
  it('quantizes to the step and never exceeds the max', () => {
    expect(sliderValueAt(0.5, 294, 10)).toBe(150)
    expect(sliderValueAt(0.02, 294, 10)).toBe(10)
    expect(sliderValueAt(1, 294, 10)).toBe(290)
    expect(sliderValueAt(1, 295, 10)).toBe(295)
  })
})
