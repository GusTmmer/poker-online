export type Suit = 'H' | 'D' | 'C' | 'S'

export interface ParsedCard {
  rank: string
  suit: Suit
  glyph: string
  color: 'red' | 'black'
}

const SUIT_GLYPHS: Record<Suit, string> = {
  H: '♥',
  D: '♦',
  C: '♣',
  S: '♠',
}

export function parseCard(card: string): ParsedCard {
  const suit = card.slice(-1) as Suit
  const rank = card.slice(0, -1)
  return {
    rank,
    suit,
    glyph: SUIT_GLYPHS[suit],
    color: suit === 'H' || suit === 'D' ? 'red' : 'black',
  }
}
