import { Container, Graphics, Text, TextStyle } from 'pixi.js'
import type { SceneState } from './sceneTypes'

/** A single poker chip sprite (clay-gold with white inlay pips and a specular highlight). */
export function chipSprite(): Container {
  const c = new Container()
  const g = new Graphics()
  const R = 9

  // Drop shadow
  g.circle(0.8, 1.1, R).fill({ color: 0x000000, alpha: 0.28 })

  // Chip body — dark gold clay (outer ring area)
  g.circle(0, 0, R).fill({ color: 0xb07d18 })

  // 4 white inlay pips at cardinal positions (circles, not arcs — avoids path artefacts)
  for (let i = 0; i < 4; i++) {
    const angle = (i / 4) * Math.PI * 2
    g.circle(R * 0.73 * Math.cos(angle), R * 0.73 * Math.sin(angle), 2.1)
      .fill({ color: 0xffffff, alpha: 0.78 })
  }

  // Outer ring stroke
  g.circle(0, 0, R).stroke({ color: 0x7a5510, width: 1.2 })

  // Inner insert ring border then bright center
  g.circle(0, 0, R * 0.51).fill({ color: 0xb07d18 })
  g.circle(0, 0, R * 0.43).fill({ color: 0xf0d06a })

  // Specular highlight — small bright ellipse top-left, simulates light source
  g.ellipse(-R * 0.24, -R * 0.31, R * 0.38, R * 0.24).fill({ color: 0xffffff, alpha: 0.42 })

  c.addChild(g)
  return c
}

export function spawnFlyingChip(scene: SceneState, fromX: number, fromY: number) {
  const s = chipSprite()
  s.position.set(fromX, fromY)
  scene.animLayer.addChild(s)
  scene.flyingChips.push({ sprite: s, fromX, fromY, elapsed: 0, duration: 550, done: false })
}

export function spawnWinnerChips(scene: SceneState, toX: number, toY: number) {
  for (let i = 0; i < 5; i++) {
    const s = chipSprite()
    s.alpha = 0; s.scale.set(0.4)
    scene.animLayer.addChild(s)
    scene.winnerChips.push({ sprite: s, toX, toY, elapsed: 0, delay: i * 90, duration: 700, done: false })
  }
}

export function spawnDelta(scene: SceneState, x: number, y: number, amount: number) {
  const col = amount > 0 ? 0x7fd9a8 : 0xe2606f
  const t = new Text({
    text: amount > 0 ? `+${amount}` : String(amount),
    style: new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 17, fontWeight: 'bold', fill: col }),
  })
  t.anchor.set(0.5, 0.5)
  t.position.set(x, y)
  scene.animLayer.addChild(t)
  scene.floatingDeltas.push({ label: t, y0: y, elapsed: 0 })
}
