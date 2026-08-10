// Logical canvas dimensions — everything is authored in this space.
// The canvas scales uniformly to fill the viewport (letterboxed).
export const LW = 800
export const LH = 560

// The scene is normally centred at (LW/2, LH/2). Nudge the whole scene up by
// this many logical px so the bottom (local) player's showdown hand section —
// which extends below their seat — clears the fixed ControlBar at the bottom.
export const SCENE_Y_OFFSET = 40

// Felt dimensions in logical units
export const FELT_W = 480
export const FELT_H = 230
export const FELT_R = FELT_H / 2  // cap radius

// Stadium rings (expand = outward, shrink = inward from felt edge)
export const SEAT_GAP = 52   // px outside felt → where avatars sit
export const CARD_INSET = 30  // px inside felt → where mini card backs sit

export interface StadiumGeometry {
  w: number
  h: number
}

export const FELT_STADIUM: StadiumGeometry = { w: FELT_W, h: FELT_H }
export const SEAT_STADIUM: StadiumGeometry = { w: FELT_W + 2 * SEAT_GAP, h: FELT_H + 2 * SEAT_GAP }
export const CARD_STADIUM: StadiumGeometry = { w: FELT_W - 2 * CARD_INSET, h: FELT_H - 2 * CARD_INSET }

export interface SlotPosition {
  x: number  // logical px from canvas center
  y: number
  angleDeg: number
}

/** Point on a stadium perimeter at `fraction` (0 = bottom-center, sweeps clockwise). */
export function pointOnStadium({ w, h }: StadiumGeometry, fraction: number): { x: number; y: number } {
  const straight = w - h
  const hs = straight / 2
  const r = h / 2
  const capLen = Math.PI * r
  const perimeter = 2 * straight + Math.PI * h

  let s = (((fraction % 1) + 1) % 1) * perimeter

  if (s <= hs) return { x: s, y: r }
  s -= hs
  if (s <= capLen) {
    const theta = s / r
    return { x: hs + r * Math.sin(theta), y: r * Math.cos(theta) }
  }
  s -= capLen
  if (s <= straight) return { x: hs - s, y: -r }
  s -= straight
  if (s <= capLen) {
    const theta = Math.PI + s / r
    return { x: -hs + r * Math.sin(theta), y: r * Math.cos(theta) }
  }
  s -= capLen
  return { x: -hs + Math.min(s, hs), y: r }
}

/**
 * Returns the position for `slot` on the given stadium geometry,
 * rotated so that `mySlot` always ends up at the bottom (fraction = 0).
 */
export function slotPosition(slot: number, mySlot: number, seatCount: number, stadium: StadiumGeometry): SlotPosition {
  const displayed = (slot - mySlot + seatCount) % seatCount
  const pt = pointOnStadium(stadium, displayed / seatCount)
  const angleDeg = (Math.atan2(pt.y, pt.x) * 180) / Math.PI
  return { x: pt.x, y: pt.y, angleDeg }
}
