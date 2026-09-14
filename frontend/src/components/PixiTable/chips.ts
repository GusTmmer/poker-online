import { Container, Graphics, Text, TextStyle } from 'pixi.js'
import type { SceneState } from './sceneTypes'

/** A single poker chip sprite: gold clay with ivory edge spots, like a real casino chip. */
export function chipSprite(): Container {
  const c = new Container()
  const g = new Graphics()
  const R = 9

  // Drop shadow
  g.circle(0.8, 1.1, R).fill({ color: 0x000000, alpha: 0.3 })

  // Chip body — dark gold clay
  g.circle(0, 0, R).fill({ color: 0xb07d18 })

  // 6 ivory edge spots around the rim
  for (let i = 0; i < 6; i++) {
    const a0 = (i / 6) * Math.PI * 2 - 0.26
    g.arc(0, 0, R - 1.1, a0, a0 + 0.52).stroke({ color: 0xf5efdc, alpha: 0.9, width: 2.2 })
  }

  // Outer ring stroke
  g.circle(0, 0, R).stroke({ color: 0x7a5510, width: 1.2 })

  // Inner insert: hairline ring, then the bright clay center
  g.circle(0, 0, R * 0.55).stroke({ color: 0xf5efdc, alpha: 0.5, width: 0.8 })
  g.circle(0, 0, R * 0.45).fill({ color: 0xf0d06a })

  // Specular highlight — small bright ellipse top-left, simulates light source
  g.ellipse(-R * 0.24, -R * 0.31, R * 0.36, R * 0.22).fill({ color: 0xffffff, alpha: 0.4 })

  c.addChild(g)
  return c
}

interface Point { x: number; y: number }

/**
 * A bet sliding into the middle: a few chips leave the player's pile one after another
 * and land on the pot — more chips for a bigger bet (1 per doubling of the big blind, up to 5).
 */
export function spawnBetChips(scene: SceneState, from: Point, to: Point, amount: number, bigBlind: number) {
  const count = Math.max(1, Math.min(5, 1 + Math.floor(Math.log2(Math.max(1, amount / Math.max(1, bigBlind))))))
  for (let i = 0; i < count; i++) {
    const s = chipSprite()
    s.alpha = 0
    s.position.set(from.x, from.y)
    scene.animLayer.addChild(s)
    // A little scatter so several chips don't stack into one sprite.
    const jitterX = (i - (count - 1) / 2) * 5
    scene.flyingChips.push({
      sprite: s, fromX: from.x, fromY: from.y, toX: to.x + jitterX, toY: to.y - i * 2,
      delay: i * 70, elapsed: 0, duration: 520, done: false,
    })
  }
}

/** The pot pushed to a winner: chips burst out of the middle and pour onto their pile. */
export function spawnWinnerChips(scene: SceneState, from: Point, to: Point) {
  for (let i = 0; i < 5; i++) {
    const s = chipSprite()
    s.alpha = 0; s.scale.set(0.4)
    scene.animLayer.addChild(s)
    scene.winnerChips.push({ sprite: s, fromX: from.x, fromY: from.y, toX: to.x, toY: to.y, elapsed: 0, delay: i * 90, duration: 700, done: false })
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
