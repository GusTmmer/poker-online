import { Container, Graphics, Text, TextStyle } from 'pixi.js'
import type { SceneState } from './sceneTypes'

// ─── action burst ─────────────────────────────────────────────────────────────
// A loud, short-lived callout at a seat when a player bets, raises, shoves or folds, so
// no action can slip by unnoticed: a ring flash plus a label that pops with overshoot,
// holds, then drifts up and fades. Aggression rings expand outward; a fold's ashen ring
// closes in on the seat. Plain Graphics (no BlurFilter), created per burst and removed
// when done.

export type BurstTone = 'raise' | 'allIn' | 'fold'

export interface ActionBurst { root: Container; ring: Graphics; label: Container; elapsed: number; tone: BurstTone }

const BURST_MS = 1500
const RING_MS = 650

const STYLE_ALL_IN = new TextStyle({
  fontFamily: 'Cinzel, Georgia, serif', fontSize: 24, fontWeight: '700', letterSpacing: 1.5,
  fill: 0xffb347, stroke: { color: 0x2a0d08, width: 5 },
  dropShadow: { color: 0x000000, alpha: 0.6, blur: 3, distance: 2, angle: Math.PI / 2 },
})

const STYLE_RAISE = new TextStyle({
  fontFamily: 'Cinzel, Georgia, serif', fontSize: 19, fontWeight: '700', letterSpacing: 1,
  fill: 0xf5d98a, stroke: { color: 0x1a0e10, width: 4 },
  dropShadow: { color: 0x000000, alpha: 0.55, blur: 3, distance: 2, angle: Math.PI / 2 },
})

const STYLE_FOLD = new TextStyle({
  fontFamily: 'Cinzel, Georgia, serif', fontSize: 19, fontWeight: '700', letterSpacing: 1.5,
  fill: 0xcfc6b8, stroke: { color: 0x14100e, width: 4 },
  dropShadow: { color: 0x000000, alpha: 0.55, blur: 3, distance: 2, angle: Math.PI / 2 },
})

const TONES: Record<BurstTone, { style: TextStyle; color: number; width: number; labelScale: number }> = {
  raise: { style: STYLE_RAISE, color: 0xf5d98a, width: 3, labelScale: 1 },
  allIn: { style: STYLE_ALL_IN, color: 0xff7a3d, width: 4, labelScale: 1.1 },
  fold:  { style: STYLE_FOLD, color: 0x9a9086, width: 3, labelScale: 1 },
}

export function spawnActionBurst(scene: SceneState, x: number, y: number, text: string, tone: BurstTone) {
  const root = new Container()
  root.position.set(x, y)

  const { style, color, width } = TONES[tone]
  const ring = new Graphics()
  ring.circle(0, 0, 26).stroke({ color, width })
  root.addChild(ring)

  const label = new Container()
  const t = new Text({ text, style })
  t.anchor.set(0.5, 0.5)
  label.addChild(t)
  // Float above the seat's centre, toward the felt for bottom-half seats and away for top ones.
  label.y = y < 0 ? 34 : -34
  label.scale.set(0.2)
  root.addChild(label)

  scene.animLayer.addChild(root)
  scene.bursts.push({ root, ring, label, elapsed: 0, tone })
}

/** Overshooting ease: 0 → ~1.15 → 1. */
function easeOutBack(t: number) {
  const c1 = 2.2, c3 = c1 + 1
  return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2)
}

export function tickBursts(scene: SceneState, dt: number) {
  for (const b of scene.bursts) {
    b.elapsed += dt

    const rp = Math.min(b.elapsed / RING_MS, 1)
    if (b.tone === 'fold') {
      // Closing in: starts wide and faint, tightens onto the seat as it fades out.
      b.ring.scale.set(2.2 - rp * 1.2)
      b.ring.alpha = Math.sin(rp * Math.PI) * 0.9
    } else {
      b.ring.scale.set(1 + rp * (b.tone === 'allIn' ? 1.6 : 1.1))
      b.ring.alpha = 1 - rp
    }

    const pop = Math.min(b.elapsed / 320, 1)
    b.label.scale.set(TONES[b.tone].labelScale * easeOutBack(pop))
    const fade = Math.max(0, (b.elapsed - 1000) / (BURST_MS - 1000))
    b.label.alpha = 1 - fade
    // Aggression drifts up and away; a fold sinks.
    b.label.pivot.y = (b.tone === 'fold' ? -1 : 1) * fade * 18
  }
  for (const b of scene.bursts) if (b.elapsed >= BURST_MS) scene.animLayer.removeChild(b.root)
  scene.bursts = scene.bursts.filter((b) => b.elapsed < BURST_MS)
}
