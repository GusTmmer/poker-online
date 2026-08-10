import { BlurFilter, Container, FillGradient, Graphics, Text, TextStyle } from 'pixi.js'
import type { PlayerView } from '../../api/types'
import { parseCard } from '../../api/cards'
import { hex } from '../../theme'

const STYLE_NAME      = new TextStyle({ fontFamily: 'Cinzel, Georgia, serif', fontSize: 12, fontWeight: '600', fill: hex.cream, letterSpacing: 0.5 })
const STYLE_CHIPS     = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 11, fill: hex.creamMuted })
const STYLE_STATUS    = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 10, fill: hex.parchment })
const STYLE_HAND_NAME = new TextStyle({ fontFamily: 'Cinzel, Georgia, serif', fontSize: 11, fontWeight: '600', fill: hex.gold, letterSpacing: 0.5 })
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

  const initial = new Text({
    text: (Array.from(player.name)[0] ?? '?').toUpperCase(),
    style: isMe ? STYLE_INITIAL_ME : STYLE_INITIAL_OTHER,
  })
  initial.anchor.set(0.5, 0.5)
  initial.y = 1
  c.addChild(initial)

  return c
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

function makeMiniCard(cardCode: string, isPocket = false): Container {
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

  const rankT = new Text({ text: rank, style: MINI_RANK })
  rankT.position.set(3, 2)
  c.addChild(rankT)

  const suitT = new Text({ text: glyph, style: color === 'red' ? MINI_SUIT_RED : MINI_SUIT_BLACK })
  suitT.anchor.set(0.5, 0.5)
  suitT.position.set(MINI_W / 2, MINI_H * 0.68)
  c.addChild(suitT)

  return c
}

// ─── build ────────────────────────────────────────────────────────────────────

/** Build all seat objects for one player. Root is centered at (0,0). */
export function buildSeat(player: PlayerView, isMe: boolean, flipLabels: boolean): SeatObjects {
  const root = new Container()
  const size = isMe ? SIZE_ME : SIZE_OTHER

  // Two dedicated halo Graphics, each drawn once and never cleared.
  // Separate objects prevent the stale-texture artefact that occurs when a single
  // Graphics+BlurFilter object is cleared and redrawn at a different scale — the
  // filter's backing texture retains old pixels from the larger shape.
  const winBlur = new BlurFilter({ strength: 10, quality: 3 })
  winBlur.padding = 40
  const winHaloGfx = new Graphics()
  winHaloGfx.filters = [winBlur]
  winHaloGfx.visible = false
  drawHaloShape(winHaloGfx, size, hex.winGreen, 1.7)
  root.addChild(winHaloGfx)

  const turnBlur = new BlurFilter({ strength: 8, quality: 3 })
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
  layoutSeat(s, isMe, flipLabels)
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
  showdownHand: PlayerView['bestHand'] | undefined,
  showdownPocket: string[] | undefined,
  flipLabels: boolean,
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
  s.badges.x = -bx / 2

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
  }

  s.readyDot.visible = !roundInProgress && isReady && player.status === 'ONLINE'

  // Showdown hand section
  s.handSection.removeChildren()
  if (showdownHand) {
    const nameT = new Text({ text: showdownHand.name, style: STYLE_HAND_NAME })
    nameT.anchor.set(0.5, 0)
    s.handSection.addChild(nameT)

    const pocket = new Set(showdownPocket ?? [])
    const MINI_GAP = 3
    let cx = -(showdownHand.cards.length * (MINI_W + MINI_GAP) - MINI_GAP) / 2
    for (const card of showdownHand.cards) {
      const mc = makeMiniCard(card, pocket.has(card))
      mc.x = cx; mc.y = nameT.height + 3
      s.handSection.addChild(mc)
      cx += MINI_W + MINI_GAP
    }
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
    if (badgeItems.length > 0) { y -= BADGE_H + 2; s.badges.y = y }

    s.handSection.x = 0; s.handSection.y = avatarH / 2 + LINE_GAP
  } else {
    s.betTag.y = -avatarH / 2 - 20

    let y = avatarH / 2 + LINE_GAP
    if (badgeItems.length > 0) { s.badges.y = y; y += BADGE_H + 2 + LINE_GAP } else { s.badges.y = y }
    s.nameText.y = y; y += s.nameText.height + LINE_GAP
    s.chipsText.y = y; y += s.chipsText.height + LINE_GAP
    if (s.statusContainer.children.length > 0) { s.statusContainer.y = y; y += 14 + LINE_GAP } else { s.statusContainer.y = y }
    s.handSection.x = 0; s.handSection.y = y
  }
}
