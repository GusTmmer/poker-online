import { BlurFilter, Container, FillGradient, Graphics, Text, TextStyle } from 'pixi.js'
import type { PlayerView } from '../../api/types'
import { parseCard } from '../../api/cards'
import { formatEquity, type SeatEquity } from '../../game/runoutOdds'
import { hex } from '../../theme'

const STYLE_NAME      = new TextStyle({ fontFamily: 'Cinzel, Georgia, serif', fontSize: 12, fontWeight: '600', fill: hex.cream, letterSpacing: 0.5 })
const STYLE_CHIPS     = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 11, fill: hex.creamMuted })
const STYLE_STATUS    = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 10, fill: hex.parchment })
const STYLE_HAND_NAME = new TextStyle({ fontFamily: 'Cinzel, Georgia, serif', fontSize: 11, fontWeight: '600', fill: hex.gold, letterSpacing: 0.5 })
const STYLE_EQUITY_LEAD = new TextStyle({ fontFamily: 'Cinzel, Georgia, serif', fontSize: 12, fontWeight: '700', fill: hex.goldBright })
const STYLE_EQUITY      = new TextStyle({ fontFamily: 'Cinzel, Georgia, serif', fontSize: 12, fontWeight: '600', fill: hex.gold })
const STYLE_BET       = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 11, fontWeight: 'bold', fill: hex.gold })
const STYLE_INITIAL_ME    = new TextStyle({ fontFamily: 'Cinzel, Georgia, serif', fontSize: 21, fontWeight: '700', fill: hex.goldBright })
const STYLE_INITIAL_OTHER = new TextStyle({ fontFamily: 'Cinzel, Georgia, serif', fontSize: 17, fontWeight: '700', fill: hex.gold })
const STYLE_BADGE_D   = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 10, fontWeight: 'bold', fill: hex.ink })
const STYLE_BADGE_SM  = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 7, fontWeight: 'bold', fill: hex.cream })

const MINI_W = 28
const MINI_H = 38
const MINI_RANK       = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 12, fontWeight: 'bold', fill: 0x22201d })
const MINI_SUIT_RED   = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 16, fill: 0xa8233a })
const MINI_SUIT_BLACK = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 16, fill: 0x22201d })
const MINI_SIMPLE_RANK_RED   = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 16, fontWeight: 'bold', fill: 0xa8233a })
const MINI_SIMPLE_RANK_BLACK = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 16, fontWeight: 'bold', fill: 0x22201d })
const MINI_SIMPLE_SUIT_RED   = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 17, fill: 0xa8233a })
const MINI_SIMPLE_SUIT_BLACK = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 17, fill: 0x22201d })

// Medallion diameters — the local player gets a slightly larger seat.
const SIZE_ME = 44
const SIZE_OTHER = 36

export interface SeatObjects {
  root: Container
  avatar: Container
  /** Gold glow shown when it's this player's turn. Drawn once; toggled via visible+alpha. */
  turnHaloGfx: Graphics
  /** Green glow shown when this player won the pot. Drawn once; toggled via visible+alpha. */
  winHaloGfx: Graphics
  nameText: Text
  chipsText: Text
  betTag: Container
  betText: Text
  badges: Container
  statusContainer: Container
  readyDot: Graphics
  handSection: Container
  chipsValue: number
  chipsDisplayValue: number
  /** Drives the pulsing alpha animation in tick(). */
  isNextToAct: boolean
}

// ─── avatar medallion ─────────────────────────────────────────────────────────
// A club-member medallion: dark enamel face, double gold ring, serif initial.

function makeAvatar(player: PlayerView, isMe: boolean): Container {
  const size = isMe ? SIZE_ME : SIZE_OTHER
  const r = size / 2
  const c = new Container()

  const g = new Graphics()
  // Cast shadow
  g.circle(1, 1.5, r).fill({ color: 0x000000, alpha: 0.4 })
  // Enamel face — lit from the top-left
  const enamel = new FillGradient({
    type: 'radial',
    center: { x: 0.38, y: 0.32 },
    innerRadius: 0,
    outerCenter: { x: 0.5, y: 0.5 },
    outerRadius: 0.62,
    colorStops: [
      { offset: 0, color: 0x3d2027 },
      { offset: 0.7, color: 0x241016 },
      { offset: 1, color: 0x160a0d },
    ],
  })
  g.circle(0, 0, r).fill(enamel)
  // Double gold ring: bright outer band, hairline inner ring
  g.circle(0, 0, r).stroke({ color: isMe ? hex.goldBright : hex.gold, width: isMe ? 2.2 : 1.8 })
  g.circle(0, 0, r - 3).stroke({ color: hex.goldDark, alpha: 0.7, width: 0.8 })
  // Specular arc where light catches the rim
  g.arc(0, 0, r - 1, -Math.PI * 0.85, -Math.PI * 0.35).stroke({ color: 0xffffff, alpha: 0.25, width: 1 })
  c.addChild(g)

  if (player.botPersonality) {
    c.addChild(makeBotGlyph(r))
    return c
  }

  const initial = new Text({
    text: (Array.from(player.name)[0] ?? '?').toUpperCase(),
    style: isMe ? STYLE_INITIAL_ME : STYLE_INITIAL_OTHER,
  })
  initial.anchor.set(0.5, 0.5)
  initial.y = 1
  c.addChild(initial)

  return c
}

/** A small gold automaton head — antenna, rounded face, two eyes — in place of a computer player's initial. */
function makeBotGlyph(r: number): Graphics {
  const u = r / 18
  const g = new Graphics()
  g.moveTo(0, -9 * u).lineTo(0, -12.5 * u).stroke({ color: hex.gold, width: 1.4 * u })
  g.circle(0, -13.5 * u, 1.8 * u).fill({ color: hex.goldBright })
  g.roundRect(-8.5 * u, -9 * u, 17 * u, 14 * u, 3.5 * u).stroke({ color: hex.gold, width: 1.6 * u })
  g.circle(-3.6 * u, -2.5 * u, 2 * u).fill({ color: hex.goldBright })
  g.circle(3.6 * u, -2.5 * u, 2 * u).fill({ color: hex.goldBright })
  g.moveTo(-3.5 * u, 2 * u).lineTo(3.5 * u, 2 * u).stroke({ color: hex.gold, width: 1.2 * u })
  g.y = 3 * u
  return g
}

const PERSONALITY_LABEL: Record<NonNullable<PlayerView['botPersonality']>, string> = {
  AGGRESSIVE: 'cpu · aggressive',
  BALANCED: 'cpu · balanced',
  DEFENSIVE: 'cpu · defensive',
}

// ─── halo ─────────────────────────────────────────────────────────────────────

/**
 * Draws a single oversized circle onto `gfx`, sized `scale`× the medallion radius.
 * With a BlurFilter on the Graphics this reads as a soft glow behind the seat.
 * Called ONCE per halo object at build time — the content is never cleared or
 * redrawn, which prevents the stale-texture artefact that appeared when
 * clear()+redraw changed the graphics bounds mid-session and the filter's
 * backing texture retained old pixels.
 */
function drawHaloShape(gfx: Graphics, size: number, color: number, scale: number) {
  gfx.circle(0, 0, (size / 2) * scale).fill({ color, alpha: 1 })
}

// ─── mini card ────────────────────────────────────────────────────────────────

/** How a seat is laid out: labels above the avatar for seats across the top, and the phone layout. */
export interface SeatLayout {
  flipLabels: boolean
  compact: boolean
}

function makeMiniCard(cardCode: string, isPocket: boolean, simple: boolean): Container {
  const c = new Container()
  const { rank, glyph, color } = parseCard(cardCode)

  // Pocket cards get a thin gold ring 2px outside the card edge — a quiet
  // "this one is theirs" marker among the five best-hand cards at showdown.
  if (isPocket) {
    const ring = new Graphics()
    ring.roundRect(-2, -2, MINI_W + 4, MINI_H + 4, 5).stroke({ color: hex.gold, alpha: 0.85, width: 1.5 })
    c.addChild(ring)
  }

  const bg = new Graphics()
  bg.roundRect(0.5, 1, MINI_W, MINI_H, 3).fill({ color: 0x000000, alpha: 0.25 })
  bg.roundRect(0, 0, MINI_W, MINI_H, 3).fill({ color: hex.ivory }).stroke({ color: 0x000000, alpha: 0.22, width: 1 })
  c.addChild(bg)

  const red = color === 'red'
  if (simple) {
    // Phones: rank over suit, centred and larger — no corner index to squint at.
    const rankT = new Text({ text: rank, style: red ? MINI_SIMPLE_RANK_RED : MINI_SIMPLE_RANK_BLACK })
    rankT.anchor.set(0.5, 0.5)
    rankT.position.set(MINI_W / 2, MINI_H * 0.3)
    const suitT = new Text({ text: glyph, style: red ? MINI_SIMPLE_SUIT_RED : MINI_SIMPLE_SUIT_BLACK })
    suitT.anchor.set(0.5, 0.5)
    suitT.position.set(MINI_W / 2, MINI_H * 0.72)
    c.addChild(rankT, suitT)
    return c
  }

  const rankT = new Text({ text: rank, style: MINI_RANK })
  rankT.position.set(3, 2)
  c.addChild(rankT)

  const suitT = new Text({ text: glyph, style: red ? MINI_SUIT_RED : MINI_SUIT_BLACK })
  suitT.anchor.set(0.5, 0.5)
  suitT.position.set(MINI_W / 2, MINI_H * 0.68)
  c.addChild(suitT)

  return c
}

// ─── showdown hand row ────────────────────────────────────────────────────────

const MINI_GAP = 3
const DIVIDER_GAP = 7   // space either side of the pocket | board divider
const WIDE_ROW_SCALE = 0.88

/**
 * Fills `section` with a player's showdown cards, centered at x = 0:
 *
 *     HAND NAME
 *     [pocket₁ pocket₂] ┊ [board cards that complete the hand]
 *
 * Both pocket cards always sit on the left, so a player's own cards are visible
 * even when the best five ignore them: a pocket card that plays gets the gold ring,
 * one that doesn't is dimmed. Only the board cards the hand uses follow the
 * divider, so no card is drawn twice and the row is 5–7 cards wide.
 *
 * With no hand yet (pocket cards tabled during an all-in runout) only the pair is
 * shown. With no pocket cards known, the hand's five cards are shown as-is.
 */
/** What a seat shows about its hand at showdown; all empty outside one. */
export interface SeatShowdown {
  hand?: PlayerView['bestHand']
  pocket?: string[]
  /** Chance of winning, shown beside tabled cards while an all-in board is run out. */
  equity?: SeatEquity
}

function buildHandSection(section: Container, { hand = null, pocket: pocketCards = [], equity }: SeatShowdown, compact: boolean) {
  let rowY = 0
  if (hand) {
    const nameT = new Text({ text: hand.name, style: STYLE_HAND_NAME })
    nameT.anchor.set(0.5, 0)
    section.addChild(nameT)
    rowY = nameT.height + 3
  }

  const row = new Container()
  row.y = rowY
  section.addChild(row)

  const inHand = new Set(hand?.cards ?? [])
  const pocket = pocketCards
  // Phones show just the pocket pair (the board is on the felt); otherwise the board cards the hand uses follow.
  const board = hand && !compact ? hand.cards.filter((c) => !pocket.includes(c)) : []

  let x = 0
  const place = (card: Container) => {
    card.x = x
    row.addChild(card)
    x += MINI_W + MINI_GAP
  }

  for (const card of pocket) {
    const plays = hand != null && inHand.has(card)
    const mc = makeMiniCard(card, plays, compact)
    if (hand && !plays) mc.alpha = 0.4
    place(mc)
  }

  if (pocket.length > 0 && board.length > 0) {
    x += DIVIDER_GAP - MINI_GAP
    const divider = new Graphics()
    divider.moveTo(0, 3).lineTo(0, MINI_H - 3).stroke({ color: hex.bronze, alpha: 0.9, width: 1 })
    divider.x = x
    row.addChild(divider)
    x += DIVIDER_GAP
  }

  for (const card of board) place(makeMiniCard(card, false, compact))

  if (equity) {
    const pill = makeEquityPill(equity)
    pill.x = x + EQUITY_GAP - MINI_GAP
    pill.y = (MINI_H - pill.height) / 2
    row.addChild(pill)
    x += EQUITY_GAP - MINI_GAP + pill.width + MINI_GAP
  }

  const width = x - MINI_GAP
  const scale = pocket.length + board.length >= 7 ? WIDE_ROW_SCALE : 1
  row.scale.set(scale)
  row.x = (-width * scale) / 2
}

const EQUITY_GAP = 6

/** The broadcast-style win percentage: gold on a dark glass pill, brightest for the hand in front. */
function makeEquityPill({ value, leading }: SeatEquity): Container {
  const c = new Container()
  const text = new Text({ text: formatEquity(value), style: leading ? STYLE_EQUITY_LEAD : STYLE_EQUITY })
  const w = text.width + 12
  const h = text.height + 6
  const bg = new Graphics()
  bg.roundRect(0, 0, w, h, h / 2)
    .fill({ color: 0x000000, alpha: 0.6 })
    .stroke({ color: leading ? hex.goldBright : hex.gold, alpha: leading ? 0.9 : 0.5, width: 1 })
  text.anchor.set(0.5, 0.5)
  text.position.set(w / 2, h / 2)
  c.addChild(bg, text)
  if (value === 0) c.alpha = 0.5
  return c
}

// ─── build ────────────────────────────────────────────────────────────────────

/** Build all seat objects for one player. Root is centered at (0,0). */
export function buildSeat(player: PlayerView, isMe: boolean, layout: SeatLayout): SeatObjects {
  const root = new Container()
  const size = isMe ? SIZE_ME : SIZE_OTHER

  // Two dedicated halo Graphics, each drawn once and never cleared.
  // Separate objects prevent the stale-texture artefact that occurs when a single
  // Graphics+BlurFilter object is cleared and redrawn at a different scale — the
  // filter's backing texture retains old pixels from the larger shape.
  // resolution 'inherit': filters default to 1×, which caps the filtered halo below the canvas'
  // real pixel density and renders it visibly soft/pixelated on HiDPI or scaled-up canvases.
  const winBlur = new BlurFilter({ strength: 10, quality: 3, resolution: 'inherit' })
  winBlur.padding = 40
  const winHaloGfx = new Graphics()
  winHaloGfx.filters = [winBlur]
  winHaloGfx.visible = false
  drawHaloShape(winHaloGfx, size, hex.winGreen, 1.7)
  root.addChild(winHaloGfx)

  const turnBlur = new BlurFilter({ strength: 8, quality: 3, resolution: 'inherit' })
  turnBlur.padding = 24
  const turnHaloGfx = new Graphics()
  turnHaloGfx.filters = [turnBlur]
  turnHaloGfx.visible = false
  drawHaloShape(turnHaloGfx, size, hex.gold, 1.3)
  root.addChild(turnHaloGfx)

  const avatar = makeAvatar(player, isMe)
  root.addChild(avatar)

  const { container: betTag, text: betText } = makeBetTagPair()
  betTag.visible = false
  root.addChild(betTag)

  const badges = new Container()
  root.addChild(badges)

  const nameText = new Text({ text: player.name.slice(0, 10), style: STYLE_NAME })
  nameText.anchor.set(0.5, 0)
  root.addChild(nameText)

  const chipsText = new Text({ text: formatChips(player.chips), style: STYLE_CHIPS })
  chipsText.anchor.set(0.5, 0)
  root.addChild(chipsText)

  const statusContainer = new Container()
  root.addChild(statusContainer)

  const readyDot = new Graphics()
  readyDot.circle(0, 0, 4).fill({ color: hex.winGreen }).stroke({ color: hex.bgDark, width: 1.5 })
  readyDot.visible = false
  root.addChild(readyDot)

  const handSection = new Container()
  root.addChild(handSection)

  const s: SeatObjects = {
    root, avatar, turnHaloGfx, winHaloGfx, nameText, chipsText, betTag, betText,
    badges, statusContainer, readyDot, handSection,
    chipsValue: player.chips, chipsDisplayValue: player.chips,
    isNextToAct: false,
  }
  layoutSeat(s, isMe, layout.flipLabels)
  return s
}

export function formatChips(chips: number): string {
  return `${Math.round(chips).toLocaleString('en-US')} chips`
}

function makeBetTagPair(): { container: Container; text: Text } {
  const c = new Container()
  const bg = new Graphics()
  const text = new Text({ text: '', style: STYLE_BET })
  text.anchor.set(0.5, 0.5)
  c.addChild(bg, text)
  return { container: c, text }
}

// ─── layout ───────────────────────────────────────────────────────────────────

const LINE_GAP = 4

function layoutSeat(s: SeatObjects, isMe: boolean, flipLabels: boolean) {
  const size = isMe ? SIZE_ME : SIZE_OTHER
  const avatarH = size

  s.avatar.y = 0

  // Ready-dot: on the medallion rim, upper right (45°).
  s.readyDot.x = (size / 2) * 0.74
  s.readyDot.y = -(size / 2) * 0.74

  if (flipLabels) {
    s.betTag.y = avatarH / 2 + 20
    let y = -avatarH / 2 - LINE_GAP
    y -= 14
    s.chipsText.y = y
    y -= LINE_GAP + 16
    s.nameText.y = y
  } else {
    s.betTag.y = -avatarH / 2 - 20
    let y = avatarH / 2 + LINE_GAP
    s.nameText.y = y
    y += 16 + LINE_GAP
    s.chipsText.y = y
  }
}

// ─── update ───────────────────────────────────────────────────────────────────

/** Update a seat's visual state to match the latest PlayerView. */
export function updateSeat(
  s: SeatObjects,
  player: PlayerView,
  isMe: boolean,
  isNextToAct: boolean,
  isWinner: boolean,
  isReady: boolean,
  roundInProgress: boolean,
  showdown: SeatShowdown,
  { flipLabels, compact }: SeatLayout,
) {
  s.isNextToAct = isNextToAct

  const faded = player.status === 'OFFLINE' || player.status === 'ELIMINATED' || player.status === 'IDLE'
  s.root.alpha = faded ? 0.45 : 1

  const size = isMe ? SIZE_ME : SIZE_OTHER

  // Halo — toggle pre-drawn blurred shapes; never clear/redraw to avoid stale filter texture.
  if (isNextToAct) {
    s.turnHaloGfx.visible = true
    s.turnHaloGfx.alpha = 0.8  // tick() will pulse this
    s.winHaloGfx.visible = false
  } else if (isWinner) {
    s.winHaloGfx.visible = true
    s.winHaloGfx.alpha = 1
    s.turnHaloGfx.visible = false
  } else {
    s.turnHaloGfx.visible = false
    s.winHaloGfx.visible = false
  }

  // Bet tag — a chip pip beside the amount, in a dark glass pill
  if (player.currentBet > 0) {
    s.betText.text = player.currentBet.toLocaleString('en-US')
    const bg = s.betTag.children[0] as Graphics
    bg.clear()
    const CHIP_R = 4
    const tw = s.betText.width + 22 + CHIP_R
    const th = s.betText.height + 8
    bg.roundRect(-tw / 2, -th / 2, tw, th, th / 2)
      .fill({ color: 0x000000, alpha: 0.5 })
      .stroke({ color: hex.gold, alpha: 0.8, width: 1 })
    const chipX = -tw / 2 + CHIP_R + 6
    bg.circle(chipX, 0, CHIP_R).fill({ color: hex.gold })
    bg.circle(chipX, 0, CHIP_R).stroke({ color: hex.goldDark, width: 1 })
    bg.circle(chipX, 0, CHIP_R * 0.45).fill({ color: hex.goldBright })
    s.betText.position.set(CHIP_R + 3, 0)
    s.betTag.visible = true
  } else {
    s.betTag.visible = false
  }

  s.chipsValue = player.chips

  // Badges — the dealer button is a real poker artifact: an ivory disc. The
  // blinds are smaller dark discs so the button stays the loudest of the three.
  s.badges.removeChildren()
  interface BadgeDef { label: string; r: number; fill: number; rim: number; style: TextStyle }
  const badgeItems: BadgeDef[] = []
  if (player.isDealer)     badgeItems.push({ label: 'D',  r: 8,   fill: hex.ivory,    rim: hex.goldMid, style: STYLE_BADGE_D })
  if (player.isSmallBlind) badgeItems.push({ label: 'SB', r: 7.5, fill: 0x1a3a6b,     rim: 0x4a6fa5,    style: STYLE_BADGE_SM })
  if (player.isBigBlind)   badgeItems.push({ label: 'BB', r: 7.5, fill: hex.oxblood,  rim: 0x8a4a56,    style: STYLE_BADGE_SM })

  let bx = 0
  for (const b of badgeItems) {
    const bc  = new Container()
    const bbg = new Graphics()
    bbg.circle(b.r, b.r, b.r).fill({ color: 0x000000, alpha: 0.3 })
    bbg.circle(b.r, b.r - 0.5, b.r).fill({ color: b.fill }).stroke({ color: b.rim, width: 1.2 })
    const bt = new Text({ text: b.label, style: b.style })
    bt.anchor.set(0.5, 0.5)
    bt.position.set(b.r, b.r - 0.5)
    bc.addChild(bbg, bt)
    bc.x = bx
    bx += b.r * 2 + 3
    s.badges.addChild(bc)
  }
  // Phones: badges sit beside the avatar instead of taking a row of their own.
  s.badges.x = compact ? -size / 2 - 4 - bx : -bx / 2

  // Status row
  s.statusContainer.removeChildren()
  if (player.status === 'OFFLINE' || player.status === 'IDLE') {
    const dotColor = player.status === 'OFFLINE' ? 0xe05555 : 0xe0a020
    const dot = new Graphics()
    dot.circle(0, 0, 4).fill({ color: dotColor })
    dot.x = 0; dot.y = 4
    const label = new Text({ text: player.status.toLowerCase(), style: STYLE_STATUS })
    label.x = 8; label.y = 0
    s.statusContainer.addChild(dot, label)
    s.statusContainer.x = -s.statusContainer.width / 2
  } else if (player.botPersonality && !compact) {
    const label = new Text({ text: PERSONALITY_LABEL[player.botPersonality], style: STYLE_STATUS })
    s.statusContainer.addChild(label)
    s.statusContainer.x = -s.statusContainer.width / 2
  }

  s.readyDot.visible = !roundInProgress && isReady && player.status === 'ONLINE'

  // Showdown hand section
  s.handSection.removeChildren()
  if (showdown.hand || showdown.pocket) {
    buildHandSection(s.handSection, showdown, compact)
  }

  // Re-layout vertical stack
  const avatarH = size
  const BADGE_H = 16

  s.avatar.y = 0
  s.readyDot.x = (size / 2) * 0.74
  s.readyDot.y = -(size / 2) * 0.74

  if (flipLabels) {
    s.betTag.y = avatarH / 2 + 20

    let y = -avatarH / 2 - LINE_GAP
    if (s.statusContainer.children.length > 0) {
      y -= 14; s.statusContainer.y = y; y -= LINE_GAP
    }
    y -= s.chipsText.height; s.chipsText.y = y
    y -= LINE_GAP + s.nameText.height; s.nameText.y = y
    y -= LINE_GAP
    if (compact) s.badges.y = -BADGE_H / 2
    else if (badgeItems.length > 0) { y -= BADGE_H + 2; s.badges.y = y }

    s.handSection.x = 0; s.handSection.y = avatarH / 2 + LINE_GAP
  } else {
    s.betTag.y = -avatarH / 2 - 20

    let y = avatarH / 2 + LINE_GAP
    if (compact) s.badges.y = -BADGE_H / 2
    else if (badgeItems.length > 0) { s.badges.y = y; y += BADGE_H + 2 + LINE_GAP } else { s.badges.y = y }
    s.nameText.y = y; y += s.nameText.height + LINE_GAP
    s.chipsText.y = y; y += s.chipsText.height + LINE_GAP
    if (s.statusContainer.children.length > 0) { s.statusContainer.y = y; y += 14 + LINE_GAP } else { s.statusContainer.y = y }
    s.handSection.x = 0
    // Phones: the row goes on the table side of the seat (above it), not further off the screen's edge.
    s.handSection.y = compact ? -avatarH / 2 - LINE_GAP - s.handSection.height : y
  }
}
