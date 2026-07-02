import { Container, FillGradient, Graphics, Text, TextStyle } from 'pixi.js'
import { parseCard } from '../../api/cards'
import { hex } from '../../theme'

export const CARD_W = 58
export const CARD_H = 82
export const CARD_R = 7

// Two cards side by side
export const CARD_GAP = 3
export const PAIR_W = CARD_W * 2 + CARD_GAP

const RED_PIP   = 0xa8233a
const BLACK_PIP = 0x22201d

const STYLE_RANK_RED   = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 14, fontWeight: 'bold', fill: RED_PIP })
const STYLE_RANK_BLACK = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 14, fontWeight: 'bold', fill: BLACK_PIP })
const STYLE_SUIT_SM_RED   = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 12, fill: RED_PIP })
const STYLE_SUIT_SM_BLACK = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 12, fill: BLACK_PIP })
const STYLE_PIP_RED   = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 30, fill: RED_PIP })
const STYLE_PIP_BLACK = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 30, fill: BLACK_PIP })

/** Warm ivory face stock with a hint of top-light, plus a soft cast shadow. */
function cardStock(): Graphics {
  const g = new Graphics()
  g.roundRect(1, 2, CARD_W, CARD_H, CARD_R).fill({ color: 0x000000, alpha: 0.28 })
  const stock = new FillGradient({
    type: 'linear',
    start: { x: 0, y: 0 },
    end: { x: 0, y: 1 },
    colorStops: [
      { offset: 0, color: 0xfffdf4 },
      { offset: 1, color: 0xf3ead6 },
    ],
  })
  g.roundRect(0, 0, CARD_W, CARD_H, CARD_R).fill(stock)
  g.roundRect(0, 0, CARD_W, CARD_H, CARD_R).stroke({ color: 0x000000, alpha: 0.22, width: 1 })
  return g
}

/**
 * Draws a card face: corner indices (rank over suit) top-left and mirrored
 * bottom-right, large center pip. Returns a Container sized CARD_W × CARD_H.
 */
export function makeCardFace(cardCode: string): Container {
  const c = new Container()
  const { rank, glyph, color } = parseCard(cardCode)
  const red = color === 'red'

  c.addChild(cardStock())

  // Top-left index: rank with its suit tucked under it
  const index = new Container()
  const rankT = new Text({ text: rank, style: red ? STYLE_RANK_RED : STYLE_RANK_BLACK })
  rankT.anchor.set(0.5, 0)
  const suitT = new Text({ text: glyph, style: red ? STYLE_SUIT_SM_RED : STYLE_SUIT_SM_BLACK })
  suitT.anchor.set(0.5, 0)
  suitT.y = rankT.height - 2
  index.addChild(rankT, suitT)
  index.position.set(9, 4)
  c.addChild(index)

  // Bottom-right index — rotated 180°, as on a real card
  const index2 = new Container()
  const rankT2 = new Text({ text: rank, style: red ? STYLE_RANK_RED : STYLE_RANK_BLACK })
  rankT2.anchor.set(0.5, 0)
  const suitT2 = new Text({ text: glyph, style: red ? STYLE_SUIT_SM_RED : STYLE_SUIT_SM_BLACK })
  suitT2.anchor.set(0.5, 0)
  suitT2.y = rankT2.height - 2
  index2.addChild(rankT2, suitT2)
  index2.rotation = Math.PI
  index2.position.set(CARD_W - 9, CARD_H - 4)
  c.addChild(index2)

  // Center pip
  const pip = new Text({ text: glyph, style: red ? STYLE_PIP_RED : STYLE_PIP_BLACK })
  pip.anchor.set(0.5, 0.5)
  pip.position.set(CARD_W / 2, CARD_H / 2)
  c.addChild(pip)

  return c
}

const STYLE_BACK_SPADE = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 18, fill: hex.gold })

/**
 * Draws a card back: oxblood stock, double gold frame, diagonal lattice, and a
 * gold spade medallion. Symmetric, so it reads correctly mid-flip.
 * Returns a Container sized CARD_W × CARD_H.
 */
export function makeCardBack(): Container {
  const c = new Container()

  const bg = new Graphics()
  bg.roundRect(1, 2, CARD_W, CARD_H, CARD_R).fill({ color: 0x000000, alpha: 0.28 })
  const stock = new FillGradient({
    type: 'linear',
    start: { x: 0, y: 0 },
    end: { x: 0, y: 1 },
    colorStops: [
      { offset: 0, color: hex.oxblood },
      { offset: 1, color: hex.oxbloodDeep },
    ],
  })
  bg.roundRect(0, 0, CARD_W, CARD_H, CARD_R).fill(stock)
  c.addChild(bg)

  // Diagonal lattice inside the inner frame
  const IN = 8
  const lattice = new Graphics()
  for (let i = -CARD_H; i < CARD_W + CARD_H; i += 9) {
    lattice.moveTo(i, 0).lineTo(i + CARD_H, CARD_H).stroke({ color: 0x7a2a36, alpha: 0.8, width: 1 })
    lattice.moveTo(i + CARD_H, 0).lineTo(i, CARD_H).stroke({ color: 0x7a2a36, alpha: 0.8, width: 1 })
  }
  const mask = new Graphics()
  mask.roundRect(IN, IN, CARD_W - IN * 2, CARD_H - IN * 2, 3).fill({ color: 0xffffff })
  lattice.mask = mask
  c.addChild(lattice, mask)

  // Medallion: gold ring behind a spade
  const medallion = new Graphics()
  medallion.circle(CARD_W / 2, CARD_H / 2, 13).fill({ color: hex.oxbloodDeep })
  medallion.circle(CARD_W / 2, CARD_H / 2, 13).stroke({ color: hex.gold, alpha: 0.9, width: 1.2 })
  c.addChild(medallion)

  const spade = new Text({ text: '♠', style: STYLE_BACK_SPADE })
  spade.anchor.set(0.5, 0.5)
  spade.position.set(CARD_W / 2, CARD_H / 2 + 1)
  c.addChild(spade)

  // Double gold frame
  const border = new Graphics()
  border.roundRect(1.5, 1.5, CARD_W - 3, CARD_H - 3, CARD_R - 1).stroke({ color: hex.gold, width: 1.6 })
  border.roundRect(5, 5, CARD_W - 10, CARD_H - 10, 4).stroke({ color: hex.gold, alpha: 0.55, width: 0.8 })
  c.addChild(border)

  return c
}

/**
 * Simplified card back for far-away renders (seat pocket cards, deal flights),
 * which display at ~0.3× scale where the full back's lattice and medallion
 * dissolve into noise. Same CARD_W × CARD_H footprint so pair/pivot math is
 * interchangeable with makeCardBack; only chunky vector features that stay
 * crisp when scaled down: bold gold frame, oxblood stock, gold diamond inlay.
 */
export function makeMiniCardBack(): Container {
  const c = new Container()
  const g = new Graphics()

  g.roundRect(2, 3, CARD_W, CARD_H, CARD_R).fill({ color: 0x000000, alpha: 0.3 })
  const stock = new FillGradient({
    type: 'linear',
    start: { x: 0, y: 0 },
    end: { x: 0, y: 1 },
    colorStops: [
      { offset: 0, color: hex.oxblood },
      { offset: 1, color: hex.oxbloodDeep },
    ],
  })
  g.roundRect(0, 0, CARD_W, CARD_H, CARD_R).fill(stock)

  // Bold gold frame — ~1.5 px after the 0.3× scale
  g.roundRect(2.5, 2.5, CARD_W - 5, CARD_H - 5, CARD_R - 1).stroke({ color: hex.gold, width: 5 })

  // Center diamond inlay
  const cx = CARD_W / 2, cy = CARD_H / 2, dw = 13, dh = 18
  g.moveTo(cx, cy - dh).lineTo(cx + dw, cy).lineTo(cx, cy + dh).lineTo(cx - dw, cy).closePath()
    .fill({ color: hex.gold })

  c.addChild(g)
  return c
}

/** A pair of simplified mini card backs side by side, sized PAIR_W × CARD_H. */
export function makeMiniCardBackPair(): Container {
  const c = new Container()
  const left = makeMiniCardBack()
  left.x = 0
  const right = makeMiniCardBack()
  right.x = CARD_W + CARD_GAP
  c.addChild(left, right)
  return c
}

/** Five card backs stacked with a 1-px step offset per layer, giving a deck illusion. */
export function makeDeck(): Container {
  const c = new Container()
  // Draw from bottom layer to top so the top card renders last (highest z-order).
  // Each deeper layer is offset +1 px right and +1 px down; top card sits at (0, 0).
  for (let i = 4; i >= 0; i--) {
    const card = makeCardBack()
    card.x = i
    card.y = i
    c.addChild(card)
  }
  return c
}

/** A pair of card backs side by side. Returns a Container sized PAIR_W × CARD_H. */
export function makeCardBackPair(): Container {
  const c = new Container()
  const left = makeCardBack()
  left.x = 0
  const right = makeCardBack()
  right.x = CARD_W + CARD_GAP
  c.addChild(left, right)
  return c
}

/** A pair of face-up cards. Returns a Container sized PAIR_W × CARD_H. */
export function makeCardFacePair(cards: string[]): Container {
  const c = new Container()
  const left = makeCardFace(cards[0])
  left.x = 0
  const right = makeCardFace(cards[1])
  right.x = CARD_W + CARD_GAP
  c.addChild(left, right)
  return c
}
