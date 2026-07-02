import { makeCardFace } from './drawCard'
import { formatChips } from './drawSeat'
import {
  DECK_DELAY_MS, SLIDE_MS, FLIP_PAUSE_MS, FLIP_STAGGER_MS, FLIP_HALF_MS, DEAL_FLIGHT_MS,
} from './constants'
import { easeIn, easeOut, lerp, tickTweens } from './tween'
import type { SceneState } from './sceneTypes'

// ─── tick ─────────────────────────────────────────────────────────────────────
// The per-frame animation loop, driven by the Pixi Ticker. Steps every active
// animation (deal flight, community slide/flip, flying/winner chips, floating
// deltas, rolling chip counts, halo pulse) and the generic tween pool. Reads and
// mutates scene animation state in place; never touches React or the frame bus.
export function tick(scene: SceneState, dt: number) {
  // Deal cards
  for (const dc of scene.dealCards) {
    if (dc.done) continue
    dc.elapsed += dt
    const t = dc.elapsed - dc.delay
    if (t <= 0) continue
    const p = Math.min(t / DEAL_FLIGHT_MS, 1), e = easeOut(p)
    dc.sprite.x = lerp(0, dc.toX, e)
    dc.sprite.y = lerp(0, dc.toY, e)
    dc.sprite.alpha = p < 0.12 ? p / 0.12 : 1
    if (p >= 1) dc.done = true
  }

  // Community card flip
  if (scene.commDeck) {
    const d = scene.commDeck
    d.elapsed += dt
    if (d.elapsed < DECK_DELAY_MS)
      d.sprite.alpha = d.elapsed / DECK_DELAY_MS
    else if (d.elapsed > d.hideAt) {
      d.sprite.alpha = Math.max(0, 1 - (d.elapsed - d.hideAt) / 180)
      if (d.sprite.alpha <= 0) { scene.communityRow.removeChild(d.sprite); scene.commDeck = null }
    } else {
      d.sprite.alpha = 1
    }
  }

  for (const cc of scene.commCards) {
    if (cc.phase === 'done') continue
    cc.elapsed += dt

    if (cc.phase === 'pre' || cc.phase === 'slide') {
      const slideT = cc.elapsed - DECK_DELAY_MS
      if (slideT < 0) { cc.outerContainer.alpha = 0; continue }
      cc.outerContainer.alpha = Math.min(slideT / 60, 1)
      const p = Math.min(slideT / SLIDE_MS, 1), e = easeOut(p)
      cc.outerContainer.x = lerp(cc.startX, cc.targetX, e)
      cc.outerContainer.y = lerp(cc.startY, 0, e)
      cc.phase = p < 1 ? 'slide' : 'flip-out'
    }

    if (cc.phase === 'flip-out') {
      const flipStart = DECK_DELAY_MS + SLIDE_MS + FLIP_PAUSE_MS + cc.batchIndex * FLIP_STAGGER_MS
      const t = cc.elapsed - flipStart
      if (t < 0) continue
      const p = Math.min(t / FLIP_HALF_MS, 1)
      cc.innerContainer.scale.x = 1 - p
      if (p >= 1) {
        cc.innerContainer.removeChildren()
        const face = makeCardFace(cc.cardCode)
        cc.innerContainer.addChild(face)
        cc.phase = 'flip-in'
      }
    }

    if (cc.phase === 'flip-in') {
      const flipStart = DECK_DELAY_MS + SLIDE_MS + FLIP_PAUSE_MS + cc.batchIndex * FLIP_STAGGER_MS + FLIP_HALF_MS
      const t = cc.elapsed - flipStart
      if (t < 0) continue
      const p = Math.min(t / FLIP_HALF_MS, 1)
      cc.innerContainer.scale.x = p
      if (p >= 1) { cc.innerContainer.scale.x = 1; cc.phase = 'done' }
    }
  }

  // Flying chips
  for (const fc of scene.flyingChips) {
    if (fc.done) continue
    fc.elapsed += dt
    const p = Math.min(fc.elapsed / fc.duration, 1), e = easeIn(p)
    fc.sprite.x = lerp(fc.fromX, 0, e)
    fc.sprite.y = lerp(fc.fromY, 0, e)
    fc.sprite.alpha = 1 - p
    if (p >= 1) { scene.animLayer.removeChild(fc.sprite); fc.done = true }
  }
  scene.flyingChips = scene.flyingChips.filter((fc) => !fc.done)

  // Winner chips
  for (const wc of scene.winnerChips) {
    if (wc.done) continue
    wc.elapsed += dt
    const t = wc.elapsed - wc.delay
    if (t < 0) continue
    const p = Math.min(t / wc.duration, 1)
    wc.sprite.x = lerp(0, wc.toX, p)
    wc.sprite.y = lerp(0, wc.toY, p)
    wc.sprite.alpha = p < 0.08 ? p/0.08 : p < 0.8 ? 1 : 1 - (p-0.8)/0.2
    const sc = p < 0.08 ? lerp(0.4, 1.1, p/0.08) : p < 0.5 ? lerp(1.1, 1, (p-0.08)/0.42) : lerp(1, 0.5, (p-0.5)/0.5)
    wc.sprite.scale.set(sc)
    if (p >= 1) { scene.animLayer.removeChild(wc.sprite); wc.done = true }
  }
  scene.winnerChips = scene.winnerChips.filter((wc) => !wc.done)

  // Floating deltas
  for (const fd of scene.floatingDeltas) {
    fd.elapsed += dt
    const p = Math.min(fd.elapsed / 1100, 1)
    fd.label.y = fd.y0 - p * 50
    fd.label.alpha = p < 0.15 ? p/0.15 : p < 0.7 ? 1 : 1 - (p-0.7)/0.3
    if (p >= 1) scene.animLayer.removeChild(fd.label)
  }
  scene.floatingDeltas = scene.floatingDeltas.filter((fd) => fd.elapsed < 1100)

  // Rolling chip counts + glow-ring pulse
  const now = Date.now() / 1000
  for (const [, entry] of scene.seats) {
    const s = entry.seatObj
    if (Math.abs(s.chipsDisplayValue - s.chipsValue) >= 0.5) {
      const factor = 1 - Math.pow(0.85, dt / 16.67)
      s.chipsDisplayValue += (s.chipsValue - s.chipsDisplayValue) * factor
      s.chipsText.text = formatChips(s.chipsDisplayValue)
    }
    if (s.isNextToAct && s.turnHaloGfx.visible) {
      s.turnHaloGfx.alpha = 0.6 + 0.4 * Math.sin(now * Math.PI * 2)
    }
  }

  tickTweens(scene, dt)
}
