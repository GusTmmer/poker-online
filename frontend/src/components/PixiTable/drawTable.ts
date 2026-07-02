import { Container, FillGradient, Graphics, Text, TextStyle } from 'pixi.js'
import { hex } from '../../theme'
import { FELT_W, FELT_H } from './layout'

// The table is the visual signature of the whole app: a mahogany rail with a
// champagne-gold inlay pinstripe around an emerald felt lit by a radial pool of
// light, with an engraved betting line and a faint club crest. Drawn once at
// startup; nothing here is ever updated per-frame.

export const RAIL = 24

export function drawTable(): Container {
  const c = new Container()
  const W = FELT_W, H = FELT_H, R = H / 2
  const g = new Graphics()

  // Shadow the table casts on the floor — grounds it in the room.
  g.roundRect(-W / 2 - RAIL - 16, -H / 2 - RAIL - 10, W + 2 * (RAIL + 16), H + 2 * (RAIL + 10) + 14, R + RAIL + 16)
    .fill({ color: 0x000000, alpha: 0.38 })

  // Mahogany rail — vertical sheen: lit on top, falling into shadow below.
  const wood = new FillGradient({
    type: 'linear',
    start: { x: 0, y: 0 },
    end: { x: 0, y: 1 },
    colorStops: [
      { offset: 0, color: hex.railLight },
      { offset: 0.45, color: hex.railMid },
      { offset: 1, color: hex.railDark },
    ],
  })
  g.roundRect(-W / 2 - RAIL, -H / 2 - RAIL, W + RAIL * 2, H + RAIL * 2, R + RAIL).fill(wood)
  g.roundRect(-W / 2 - RAIL, -H / 2 - RAIL, W + RAIL * 2, H + RAIL * 2, R + RAIL)
    .stroke({ color: 0x000000, alpha: 0.55, width: 2 })
  // Thin highlight where overhead light catches the outer wood edge
  g.roundRect(-W / 2 - RAIL + 2, -H / 2 - RAIL + 2, W + RAIL * 2 - 4, H + RAIL * 2 - 4, R + RAIL - 2)
    .stroke({ color: 0xa8703e, alpha: 0.35, width: 1.5 })

  // Champagne-gold pinstripe inlaid into the middle of the rail
  const inset = RAIL / 2
  g.roundRect(-W / 2 - inset, -H / 2 - inset, W + inset * 2, H + inset * 2, R + inset)
    .stroke({ color: hex.gold, alpha: 0.85, width: 1.4 })

  // Emerald felt — radial pool of light, slightly above center where the
  // community cards land.
  const felt = new FillGradient({
    type: 'radial',
    center: { x: 0.5, y: 0.42 },
    innerRadius: 0,
    outerCenter: { x: 0.5, y: 0.42 },
    outerRadius: 0.62,
    colorStops: [
      { offset: 0, color: hex.feltLight },
      { offset: 0.55, color: hex.feltMid },
      { offset: 0.85, color: hex.feltDark },
      { offset: 1, color: hex.feltEdge },
    ],
  })
  g.roundRect(-W / 2, -H / 2, W, H, R).fill(felt)

  // Inner shadow where the rail overhangs the felt
  g.roundRect(-W / 2 + 3, -H / 2 + 3, W - 6, H - 6, R - 3)
    .stroke({ color: 0x000000, alpha: 0.35, width: 6 })

  // Engraved betting line, just outside the ring where pocket cards sit
  const LINE = 26
  g.roundRect(-W / 2 + LINE, -H / 2 + LINE, W - LINE * 2, H - LINE * 2, R - LINE)
    .stroke({ color: hex.goldBright, alpha: 0.15, width: 1.5 })

  c.addChild(g)

  // Club crest — a barely-there spade watermark ringed by a hairline, like an
  // embroidered monogram in the felt. Content renders over it untouched.
  const crestRing = new Graphics()
  crestRing.circle(0, 0, 74).stroke({ color: hex.goldBright, alpha: 0.06, width: 1.5 })
  crestRing.circle(0, 0, 68).stroke({ color: hex.goldBright, alpha: 0.04, width: 1 })
  c.addChild(crestRing)

  const crest = new Text({
    text: '♠',
    style: new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 104, fill: hex.goldBright }),
  })
  crest.anchor.set(0.5, 0.5)
  crest.alpha = 0.055
  c.addChild(crest)

  return c
}
