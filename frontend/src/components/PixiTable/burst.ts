import { Container, Graphics, Text, TextStyle } from 'pixi.js'
import type { SceneState } from './sceneTypes'

// ─── action burst ─────────────────────────────────────────────────────────────
// A loud, short-lived callout at a seat when a player bets, raises or shoves, so
// aggression can't slip by unnoticed: an expanding ring flash plus a label that
// pops with overshoot, holds, then drifts up and fades. Plain Graphics (no
// BlurFilter), created per burst and removed when done.

export interface ActionBurst { root: Container; ring: Graphics; label: Container; elapsed: number; strong: boolean }

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

export function spawnActionBurst(scene: SceneState, x: number, y: number, text: string, strong: boolean) {
  const root = new Container()
  root.position.set(x, y)

  const color = strong ? 0xff7a3d : 0xf5d98a
  const ring = new Graphics()
  ring.circle(0, 0, 26).stroke({ color, width: strong ? 4 : 3 })
  root.addChild(ring)

  const label = new Container()
  const t = new Text({ text, style: strong ? STYLE_ALL_IN : STYLE_RAISE })
  t.anchor.set(0.5, 0.5)
  label.addChild(t)
  // Float above the seat's centre, toward the felt for bottom-half seats and away for top ones.
  label.y = y < 0 ? 34 : -34
  label.scale.set(0.2)
  root.addChild(label)

  scene.animLayer.addChild(root)
  scene.bursts.push({ root, ring, label, elapsed: 0, strong })
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
    b.ring.scale.set(1 + rp * (b.strong ? 1.6 : 1.1))
    b.ring.alpha = 1 - rp

    const pop = Math.min(b.elapsed / 320, 1)
    b.label.scale.set((b.strong ? 1.1 : 1) * easeOutBack(pop))
    const fade = Math.max(0, (b.elapsed - 1000) / (BURST_MS - 1000))
    b.label.alpha = 1 - fade
    b.label.pivot.y = fade * 18
  }
  for (const b of scene.bursts) if (b.elapsed >= BURST_MS) scene.animLayer.removeChild(b.root)
  scene.bursts = scene.bursts.filter((b) => b.elapsed < BURST_MS)
}
