import { BlurFilter, Container, Graphics, Text, TextStyle } from 'pixi.js'
import type { PlayerView } from '../../api/types'
import { parseCard } from '../../api/cards'

const STYLE_NAME      = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 13, fontWeight: 'bold', fill: 0xede1c8 })
const STYLE_CHIPS     = new TextStyle({ fontFamily: 'sans-serif', fontSize: 12, fill: 0xc9b896 })
const STYLE_BADGE     = new TextStyle({ fontFamily: 'sans-serif', fontSize: 10, fontWeight: 'bold', fill: 0x1a0e10 })
const STYLE_STATUS    = new TextStyle({ fontFamily: 'sans-serif', fontSize: 10, fill: 0xa08060 })
const STYLE_HAND_NAME = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 11, fontWeight: 'bold', fill: 0xd8b65a })
const STYLE_BET       = new TextStyle({ fontFamily: 'sans-serif', fontSize: 11, fontWeight: 'bold', fill: 0xd8b65a })

const MINI_W = 28
const MINI_H = 38
const MINI_RANK       = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 12, fontWeight: 'bold', fill: 0x1c1c1c })
const MINI_SUIT_RED   = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 16, fill: 0xa8233a })
const MINI_SUIT_BLACK = new TextStyle({ fontFamily: 'Georgia, serif', fontSize: 16, fill: 0x1c1c1c })

export interface SeatObjects {
  root: Container
  avatar: Container
  avatarGfx: Graphics
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

// ─── avatar shape ─────────────────────────────────────────────────────────────

function makeAvatarGfx(isMe: boolean): { container: Container; gfx: Graphics } {
  const size = isMe ? 38 : 32
  const c = new Container()
  const gfx = new Graphics()

  const headR   = size * 0.24
  const bodyTop = headR * 2 + 2
  const bodyH   = size * 0.52

  gfx.circle(size / 2 + 1, headR + 1, headR).fill({ color: 0x3a0d14, alpha: 0.55 })
  gfx.circle(size / 2, headR, headR).fill({ color: 0x8a2535 })
  gfx.circle(size / 2 - headR * 0.28, headR - headR * 0.28, headR * 0.42).fill({ color: 0xb84055, alpha: 0.55 })

  gfx.moveTo(1, bodyTop + 1).lineTo(size + 1, bodyTop + 1).lineTo(size / 2 + 1, bodyTop + bodyH + 1).closePath().fill({ color: 0x3a0d14, alpha: 0.45 })
  gfx.moveTo(0, bodyTop).lineTo(size, bodyTop).lineTo(size / 2, bodyTop + bodyH).closePath().fill({ color: 0x8a2535 })
  gfx.moveTo(0, bodyTop).lineTo(size * 0.35, bodyTop).lineTo(size / 2, bodyTop + bodyH).closePath().fill({ color: 0xa03045, alpha: 0.4 })

  c.addChild(gfx)
  c.pivot.set(size / 2, (headR * 2 + bodyH) / 2)
  return { container: c, gfx }
}

// ─── halo ─────────────────────────────────────────────────────────────────────

/**
 * Draws a single oversized avatar-shaped fill onto `gfx` at the given scale.
 * When combined with a BlurFilter on the parent Graphics, this produces a soft glow.
 * Called ONCE per halo object at build time — the content is never cleared or redrawn,
 * which prevents the stale-texture artefact that appeared when clear()+redraw changed
 * the graphics bounds mid-session and the filter's backing texture retained old pixels.
 */
function drawHaloShape(gfx: Graphics, size: number, color: number, sc: number) {
  const headR   = size * 0.24
  const bodyH   = size * 0.52
  const avatarH = headR * 2 + bodyH
  const bodyTop = headR * 2 + 2
  const headCy  = headR - avatarH / 2
  const bTopY   = bodyTop - avatarH / 2
  const bBotY   = bodyTop + bodyH - avatarH / 2

  gfx.circle(0, headCy, headR * sc).fill({ color, alpha: 1 })
  gfx
    .moveTo(-size / 2 * sc, bTopY)
    .lineTo( size / 2 * sc, bTopY)
    .lineTo(0, bBotY * (sc * 0.9))
    .closePath()
    .fill({ color, alpha: 1 })
}

// ─── mini card ────────────────────────────────────────────────────────────────

function makeMiniCard(cardCode: string): Container {
  const c = new Container()
  const { rank, glyph, color } = parseCard(cardCode)

  const bg = new Graphics()
  bg.roundRect(0, 0, MINI_W, MINI_H, 3).fill({ color: 0xfffdf6 }).stroke({ color: 0x000000, alpha: 0.22, width: 1 })
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
  const size = isMe ? 38 : 32

  // Two dedicated halo Graphics, each drawn once and never cleared.
  // Separate objects prevent the stale-texture artefact that occurs when a single
  // Graphics+BlurFilter object is cleared and redrawn at a different scale — the
  // filter's backing texture retains old pixels from the larger shape.
  const winBlur = new BlurFilter({ strength: 10, quality: 3 })
  winBlur.padding = 40
  const winHaloGfx = new Graphics()
  winHaloGfx.filters = [winBlur]
  winHaloGfx.visible = false
  drawHaloShape(winHaloGfx, size, 0x3ddc84, 2.2)
  root.addChild(winHaloGfx)

  const turnBlur = new BlurFilter({ strength: 8, quality: 3 })
  turnBlur.padding = 24
  const turnHaloGfx = new Graphics()
  turnHaloGfx.filters = [turnBlur]
  turnHaloGfx.visible = false
  drawHaloShape(turnHaloGfx, size, 0xd8b65a, 1.35)
  root.addChild(turnHaloGfx)

  const { container: avatar, gfx: avatarGfx } = makeAvatarGfx(isMe)
  root.addChild(avatar)

  const { container: betTag, text: betText } = makeBetTagPair()
  betTag.visible = false
  root.addChild(betTag)

  const badges = new Container()
  root.addChild(badges)

  const nameText = new Text({ text: player.name.slice(0, 10), style: STYLE_NAME })
  nameText.anchor.set(0.5, 0)
  root.addChild(nameText)

  const chipsText = new Text({ text: `${player.chips} chips`, style: STYLE_CHIPS })
  chipsText.anchor.set(0.5, 0)
  root.addChild(chipsText)

  const statusContainer = new Container()
  root.addChild(statusContainer)

  const readyDot = new Graphics()
  readyDot.circle(0, 0, 4).fill({ color: 0x3ddc84 }).stroke({ color: 0x0a0a0a, width: 1.5 })
  readyDot.visible = false
  root.addChild(readyDot)

  const handSection = new Container()
  root.addChild(handSection)

  const s: SeatObjects = {
    root, avatar, avatarGfx, turnHaloGfx, winHaloGfx, nameText, chipsText, betTag, betText,
    badges, statusContainer, readyDot, handSection,
    chipsValue: player.chips, chipsDisplayValue: player.chips,
    isNextToAct: false,
  }
  layoutSeat(s, isMe, flipLabels)
  return s
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
  const size = isMe ? 38 : 32
  const headR = size * 0.24
  const bodyH = size * 0.52
  const avatarH = headR * 2 + bodyH

  s.avatar.y = 0

  // Ready-dot: 1 px outside the avatar body right edge in root coords (+size/2).
  s.readyDot.x = size / 2 + 1
  s.readyDot.y = headR - avatarH / 2

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
  flipLabels: boolean,
) {
  s.isNextToAct = isNextToAct

  const faded = player.status === 'OFFLINE' || player.status === 'ELIMINATED' || player.status === 'IDLE'
  s.root.alpha = faded ? 0.45 : 1

  const size = isMe ? 38 : 32

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

  // Bet tag
  if (player.currentBet > 0) {
    s.betText.text = String(player.currentBet)
    const bg = s.betTag.children[0] as Graphics
    bg.clear()
    const tw = s.betText.width + 12
    const th = s.betText.height + 6
    bg.roundRect(-tw / 2, -th / 2, tw, th, 6)
      .fill({ color: 0x000000, alpha: 0.45 })
      .stroke({ color: 0xd8b65a, width: 1 })
    s.betText.position.set(0, 0)
    s.betTag.visible = true
  } else {
    s.betTag.visible = false
  }

  s.chipsValue = player.chips

  // Badges (D / SB / BB)
  s.badges.removeChildren()
  const badgeItems: { label: string; color: number }[] = []
  if (player.isDealer)     badgeItems.push({ label: 'D',  color: 0xd8b65a })
  if (player.isSmallBlind) badgeItems.push({ label: 'SB', color: 0x4a6fa5 })
  if (player.isBigBlind)   badgeItems.push({ label: 'BB', color: 0x4a6fa5 })

  let bx = 0
  for (const b of badgeItems) {
    const bc  = new Container()
    const bt  = new Text({ text: b.label, style: STYLE_BADGE })
    const bw  = bt.width + 8
    const bh  = bt.height + 4
    const bbg = new Graphics()
    bbg.roundRect(0, 0, bw, bh, 4).fill({ color: b.color })
    bt.position.set(4, 2)
    bc.addChild(bbg, bt)
    bc.x = bx
    bx += bw + 3
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

    const MINI_GAP = 3
    let cx = -(showdownHand.cards.length * (MINI_W + MINI_GAP) - MINI_GAP) / 2
    for (const card of showdownHand.cards) {
      const mc = makeMiniCard(card)
      mc.x = cx; mc.y = nameT.height + 3
      s.handSection.addChild(mc)
      cx += MINI_W + MINI_GAP
    }
  }

  // Re-layout vertical stack
  const headR = size * 0.24
  const bodyH = size * 0.52
  const avatarH = headR * 2 + bodyH

  s.avatar.y = 0
  s.readyDot.x = size / 2 + 1
  s.readyDot.y = headR - avatarH / 2

  if (flipLabels) {
    s.betTag.y = avatarH / 2 + 20

    let y = -avatarH / 2 - LINE_GAP
    if (s.statusContainer.children.length > 0) {
      y -= 14; s.statusContainer.y = y; y -= LINE_GAP
    }
    y -= s.chipsText.height; s.chipsText.y = y
    y -= LINE_GAP + s.nameText.height; s.nameText.y = y
    y -= LINE_GAP
    if (badgeItems.length > 0) { y -= 18; s.badges.y = y }

    s.handSection.x = 0; s.handSection.y = avatarH / 2 + LINE_GAP
  } else {
    s.betTag.y = -avatarH / 2 - 20

    let y = avatarH / 2 + LINE_GAP
    if (badgeItems.length > 0) { s.badges.y = y; y += 18 + LINE_GAP } else { s.badges.y = y }
    s.nameText.y = y; y += s.nameText.height + LINE_GAP
    s.chipsText.y = y; y += s.chipsText.height + LINE_GAP
    if (s.statusContainer.children.length > 0) { s.statusContainer.y = y; y += 14 + LINE_GAP } else { s.statusContainer.y = y }
    s.handSection.x = 0; s.handSection.y = y
  }
}
