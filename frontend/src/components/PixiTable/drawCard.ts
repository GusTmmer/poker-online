import { Container, Graphics, Text, TextStyle } from 'pixi.js'
import { parseCard } from '../../api/cards'

export const CARD_W = 58
export const CARD_H = 82
export const CARD_R = 7

// Two cards side by side
export const CARD_GAP = 3
export const PAIR_W = CARD_W * 2 + CARD_GAP

/** Draws a card face (rank + suit). Returns a Container sized CARD_W × CARD_H. */
export function makeCardFace(cardCode: string): Container {
  const c = new Container()
  const { rank, glyph, color } = parseCard(cardCode)

  const bg = new Graphics()
  bg.roundRect(0, 0, CARD_W, CARD_H, CARD_R)
    .fill({ color: 0xfffdf6 })
    .stroke({ color: 0x000000, alpha: 0.2, width: 1 })
  c.addChild(bg)

  const col = color === 'red' ? 0xa8233a : 0x1c1c1c

  const rankText = new Text({
    text: rank,
    style: new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 15, fontWeight: 'bold', fill: col }),
  })
  rankText.position.set(5, 4)
  c.addChild(rankText)

  const suitText = new Text({
    text: glyph,
    style: new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 32, fill: col }),
  })
  suitText.anchor.set(0.5, 0.5)
  suitText.position.set(CARD_W / 2, CARD_H / 2)
  c.addChild(suitText)

  return c
}

/** Draws a card back. Returns a Container sized CARD_W × CARD_H. */
export function makeCardBack(): Container {
  const c = new Container()

  const bg = new Graphics()
  // Diagonal stripe pattern approximated with two layers
  bg.roundRect(0, 0, CARD_W, CARD_H, CARD_R).fill({ color: 0x5c1620 })
  c.addChild(bg)

  // Stripe overlay — alternating dark/light diagonal lines via a Graphics mask approach
  const stripes = new Graphics()
  for (let i = -CARD_H; i < CARD_W + CARD_H; i += 10) {
    stripes
      .moveTo(i, 0)
      .lineTo(i + CARD_H, CARD_H)
      .stroke({ color: 0x7a1f2b, width: 5 })
  }
  // Clip stripes to rounded rect
  const mask = new Graphics()
  mask.roundRect(0, 0, CARD_W, CARD_H, CARD_R).fill({ color: 0xffffff })
  c.addChild(stripes)
  stripes.mask = mask
  c.addChild(mask)

  // Gold border
  const border = new Graphics()
  border.roundRect(0, 0, CARD_W, CARD_H, CARD_R).stroke({ color: 0xd8b65a, width: 2 })
  c.addChild(border)

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
