import { Graphics, type Container } from 'pixi.js'
import type { GameStateUpdate, PlayerView } from '../../api/types'
import { hex } from '../../theme'
import {
  SEAT_STADIUM, CARD_STADIUM, CHIP_STADIUM, CHIP_ALONG, slotPosition,
  LW, LH, SCENE_Y_OFFSET, COMPACT_CONTENT_W, PHONE_CONTENT_W, PHONE_CONTENT_H,
} from './layout'
import { drawPile, makePile, pileChipCount, pileUnit } from './chipPile'
import { buildSeat, updateSeat } from './drawSeat'
import { oddsAt } from '../../game/runoutOdds'
import { makeCardBackPair, makeCardFacePair, makeMiniCardBackPair, CARD_H, PAIR_W } from './drawCard'
import { MINI_SCALE, MY_CARD_SCALE, MY_CARD_X } from './constants'
import { tween } from './tween'
import type { SceneState } from './sceneTypes'

// ─── slot mapping ───────────────────────────────────────────────────────────
export function getSlotMap(players: PlayerView[]): Map<number, number> {
  return new Map([...players].sort((a, b) => a.id - b.id).map((p, i) => [p.id, i]))
}

/** Screen position of a player's seat, from the latest applied snapshot. */
export function seatPos(scene: SceneState, playerId: number) {
  const state = scene.lastApplied!
  const slotMap   = getSlotMap(state.players)
  const mySlot    = slotMap.get(scene.myPlayerId) ?? 0
  const seatCount = Math.max(scene.maxPlayers, state.players.length, 2)
  return slotPosition(slotMap.get(playerId) ?? 0, mySlot, seatCount, SEAT_STADIUM)
}

/** Where a player's chip pile sits on the felt — bets leave from here and winnings land here. */
export function pilePos(scene: SceneState, playerId: number) {
  const state = scene.lastApplied!
  const slotMap   = getSlotMap(state.players)
  const mySlot    = slotMap.get(scene.myPlayerId) ?? 0
  const seatCount = Math.max(scene.maxPlayers, state.players.length, 2)
  const pos = slotPosition(slotMap.get(playerId) ?? 0, mySlot, seatCount, CHIP_STADIUM, CHIP_ALONG)
  // Aim at the top of a mid-sized pile rather than the felt under it.
  return { x: pos.x, y: pos.y - 8 }
}

// ─── empty seats ──────────────────────────────────────────────────────────────
function renderEmptySeats(scene: SceneState, seatCount: number, mySlot: number, occupiedSlots: Set<number>) {
  scene.emptySeatsLayer.removeChildren()
  for (let slot = 0; slot < seatCount; slot++) {
    if (occupiedSlots.has(slot)) continue
    const pos = slotPosition(slot, mySlot, seatCount, SEAT_STADIUM)
    const g = new Graphics()
    // Vacant seat: a faint double ring, like an unclaimed medallion
    g.circle(0, 0, 19).stroke({ color: hex.gold, alpha: 0.22, width: 1.5 })
    g.circle(0, 0, 15.5).stroke({ color: hex.gold, alpha: 0.09, width: 1 })
    g.position.set(pos.x, pos.y)
    scene.emptySeatsLayer.addChild(g)
  }
}

// ─── seats ────────────────────────────────────────────────────────────────────
export function updateSeats(
  scene: SceneState,
  gameState: GameStateUpdate,
  myPlayerId: number,
  maxPlayers: number,
  showdownHands: Map<number, PlayerView['bestHand']>,
  showdownPockets: Map<number, string[]>,
  winnerPlayerIds: Set<number>,
) {
  const { players, roundStage, nextPlayerIdToAct, readyPlayerIds } = gameState
  const roundInProgress = roundStage != null
  const slotMap  = getSlotMap(players)
  const mySlot   = slotMap.get(myPlayerId) ?? 0
  const seatCount = Math.max(maxPlayers, players.length, 2)
  const occupied  = new Set<number>()
  const runoutEquities = scene.runout ? oddsAt(gameState.runoutOdds, scene.runout.boardShown) : null
  const unit = pileUnit(gameState)

  for (const player of players) {
    const slot = slotMap.get(player.id)!
    occupied.add(slot)
    const isMe   = player.id === myPlayerId
    const seatPosition = slotPosition(slot, mySlot, seatCount, SEAT_STADIUM)

    const flipLabels = seatPosition.y < -10

    const cardPos = slotPosition(slot, mySlot, seatCount, CARD_STADIUM)

    let entry = scene.seats.get(player.id)
    if (!entry) {
      const seatObj = buildSeat(player, isMe, { flipLabels, compact: scene.compact })
      seatObj.root.position.set(seatPosition.x, seatPosition.y)
      scene.seatsLayer.addChild(seatObj.root)

      const miniCards = makeMiniCardBackPair()
      miniCards.pivot.set(PAIR_W / 2, CARD_H / 2)
      miniCards.scale.set(MINI_SCALE)
      miniCards.position.set(cardPos.x, cardPos.y)
      miniCards.rotation = (cardPos.angleDeg - 90) * Math.PI / 180
      miniCards.visible = false
      scene.miniCardsLayer.addChild(miniCards)

      const pile = makePile()
      scene.chipPilesLayer.addChild(pile)

      entry = { slot, playerId: player.id, seatObj, miniCards, miniCardsVisible: false, pile, pileCount: -1 }
      scene.seats.set(player.id, entry)
    }

    // Seats move when players join or leave, so the pile is re-placed every time; it is redrawn only when it changes size.
    const pilePosition = slotPosition(slot, mySlot, seatCount, CHIP_STADIUM, CHIP_ALONG)
    entry.pile.position.set(pilePosition.x, pilePosition.y)
    const pileCount = pileChipCount(player.chips, unit)
    if (pileCount !== entry.pileCount) {
      entry.pileCount = pileCount
      drawPile(entry.pile, pileCount)
    }

    const isNextToAct = !scene.isDealing && !scene.isCommunityDealing && player.id === nextPlayerIdToAct
    const isWinner    = winnerPlayerIds.has(player.id)

    // Pocket cards show beside a best hand, or on their own — with the odds of winning — while an all-in
    // runout tables them.
    const hand = showdownHands.get(player.id)
    const tabled = scene.runout != null && player.isActive
    updateSeat(
      entry.seatObj, player, isMe, isNextToAct, isWinner,
      // Computer players are always ready.
      readyPlayerIds.includes(player.id) || player.botPersonality != null, roundInProgress,
      {
        hand,
        pocket: hand || tabled ? showdownPockets.get(player.id) : undefined,
        equity: tabled ? runoutEquities?.get(player.id) : undefined,
      },
      { flipLabels, compact: scene.compact },
    )

    // Hide mini card backs at showdown — the hand section already renders the cards. An all-in player (0 chips)
    // is still in the hand, so their cards stay in front of them.
    const showMini = !scene.isDealing && roundInProgress && player.isActive && roundStage !== 'SHOWDOWN'
    if (showMini !== entry.miniCardsVisible) {
      entry.miniCardsVisible = showMini
      if (showMini) {
        // Reset to home position — a prior fold animation may have parked the
        // cards mid-flight toward the muck.
        entry.miniCards.position.set(cardPos.x, cardPos.y)
        entry.miniCards.visible = true
        entry.miniCards.alpha = 0
        tween(scene, entry.miniCards, { alpha: 1 }, 400)
      } else {
        tween(scene, entry.miniCards, { alpha: 0 }, 350, 0, () => { entry!.miniCards.visible = false })
      }
    }
  }

  // Remove seats for departed players
  for (const [id, entry] of scene.seats) {
    if (!slotMap.has(id)) {
      scene.seatsLayer.removeChild(entry.seatObj.root)
      scene.miniCardsLayer.removeChild(entry.miniCards)
      scene.chipPilesLayer.removeChild(entry.pile)
      scene.seats.delete(id)
    }
  }

  fitShowdownRows(scene)
  renderEmptySeats(scene, seatCount, mySlot, occupied)
  updateMyCards(scene, gameState, myPlayerId, mySlot, seatCount)
}

// ─── showdown rows on a crowded table ─────────────────────────────────────────
const MIN_ROW_SCALE = 0.55
const ROW_SCALE_STEP = 0.03
const ROW_CLEARANCE = 6

interface Rect { x0: number; y0: number; x1: number; y1: number }

function rectOf(...parts: Container[]): Rect | null {
  const bounds = parts.filter((p) => p.visible).map((p) => p.getBounds()).filter((b) => b.width > 0)
  if (bounds.length === 0) return null
  return {
    x0: Math.min(...bounds.map((b) => b.minX)), y0: Math.min(...bounds.map((b) => b.minY)),
    x1: Math.max(...bounds.map((b) => b.maxX)), y1: Math.max(...bounds.map((b) => b.maxY)),
  }
}

const clash = (a: Rect, b: Rect) =>
  a.x0 < b.x1 + ROW_CLEARANCE && b.x0 < a.x1 + ROW_CLEARANCE && a.y0 < b.y1 + ROW_CLEARANCE && b.y0 < a.y1 + ROW_CLEARANCE

/**
 * Shrinks the showdown rows that would run into another seat, another row, the board or the pot, or past
 * the part of the table that's on screen — up to ten seats can show a 7-card row. Each row keeps its
 * anchor (its top edge; on phones the bottom edge, against the seat) and gives way in small steps, the
 * larger of two clashing rows first, so only the crowded spots shrink and an open table stays full size.
 */
function fitShowdownRows(scene: SceneState) {
  const entries = [...scene.seats.values()]
  const rows = entries.filter((e) => e.seatObj.handSection.children.length > 0).map((e) => {
    const section = e.seatObj.handSection
    return { playerId: e.playerId, section, anchor: section.getGlobalPosition(), natural: rectOf(section)!, scale: 1 }
  })
  if (rows.length === 0) return

  const at = ({ anchor: a, natural: n }: typeof rows[number], k: number): Rect => ({
    x0: a.x + (n.x0 - a.x) * k, y0: a.y + (n.y0 - a.y) * k,
    x1: a.x + (n.x1 - a.x) * k, y1: a.y + (n.y1 - a.y) * k,
  })
  const labels = entries.map((e) => {
    const s = e.seatObj
    return { playerId: e.playerId, rect: rectOf(s.avatar, s.nameText, s.chipsText, s.badges, s.statusContainer) }
  })
  const fixed = [rectOf(scene.communityRow), rectOf(scene.potContainer)].filter((r) => r != null)
  // What's on screen, in the canvas' logical space: the phone crop all round; otherwise the narrower of the
  // two desktop crops across — the local player's row hangs below the crop by design (see doResize).
  const centreY = LH / 2 - SCENE_Y_OFFSET
  const halfW = (scene.compact ? PHONE_CONTENT_W : COMPACT_CONTENT_W) / 2
  const area = scene.compact
    ? { x0: LW / 2 - halfW, x1: LW / 2 + halfW, y0: centreY - PHONE_CONTENT_H / 2, y1: centreY + PHONE_CONTENT_H / 2 }
    : { x0: LW / 2 - halfW, x1: LW / 2 + halfW, y0: -Infinity, y1: Infinity }

  for (const row of rows) {
    const blocked = (k: number) => {
      const r = at(row, k)
      return r.x0 < area.x0 || r.x1 > area.x1 || r.y0 < area.y0 || r.y1 > area.y1 ||
        fixed.some((f) => clash(r, f)) ||
        labels.some((l) => l.playerId !== row.playerId && l.rect != null && clash(r, l.rect))
    }
    while (row.scale > MIN_ROW_SCALE && blocked(row.scale)) row.scale = Math.max(MIN_ROW_SCALE, row.scale - ROW_SCALE_STEP)
  }

  for (let changed = true; changed;) {
    changed = false
    for (let i = 0; i < rows.length; i++) {
      for (let j = i + 1; j < rows.length; j++) {
        const a = rows[i], b = rows[j]
        if (!clash(at(a, a.scale), at(b, b.scale))) continue
        const give = (a.scale > b.scale ? [a] : b.scale > a.scale ? [b] : [a, b]).filter((r) => r.scale > MIN_ROW_SCALE)
        for (const r of give) r.scale = Math.max(MIN_ROW_SCALE, r.scale - ROW_SCALE_STEP)
        if (give.length > 0) changed = true
      }
    }
  }

  for (const row of rows) row.section.scale.set(row.scale)
}

// ─── local player face-up cards ───────────────────────────────────────────────
function updateMyCards(
  scene: SceneState,
  gameState: GameStateUpdate,
  myPlayerId: number,
  mySlot: number,
  seatCount: number,
) {
  const { myCardsContainer } = scene
  const me = gameState.players.find((p) => p.id === myPlayerId)
  if (!me) { myCardsContainer.visible = false; return }

  const seatPosition = slotPosition(mySlot, mySlot, seatCount, SEAT_STADIUM)
  myCardsContainer.position.set(MY_CARD_X, seatPosition.y)

  // Hide at showdown — the seat's hand section shows the full best hand instead.
  // Hide when inactive/eliminated and clear the stale key so the next active state re-renders.
  const shouldShow = !scene.isDealing && gameState.roundStage != null
    && gameState.roundStage !== 'SHOWDOWN' && me.isActive
  if (!shouldShow) {
    myCardsContainer.visible = false
    if (!me.isActive) scene.myCardsPocketKey = ''
    return
  }

  const pocketKey = (me.pocketCards ?? []).join(',')
  if (pocketKey === scene.myCardsPocketKey && myCardsContainer.visible) return
  scene.myCardsPocketKey = pocketKey

  myCardsContainer.removeChildren()
  myCardsContainer.visible = true

  const pair = me.pocketCards ? makeCardFacePair(me.pocketCards, scene.compact) : makeCardBackPair()
  pair.pivot.set(PAIR_W / 2, CARD_H / 2)
  pair.scale.set(MY_CARD_SCALE)
  myCardsContainer.addChild(pair)

  myCardsContainer.alpha = 0
  tween(scene, myCardsContainer, { alpha: 1 }, 400)
}

// ─── showdown hands + refresh ─────────────────────────────────────────────────
/** Best hands to show on seats: live at showdown, else whatever we retained from one. */
function currentShowdownHands(scene: SceneState): Map<number, PlayerView['bestHand']> {
  // Mid-runout the board isn't out yet: no hand names until the river lands.
  if (scene.runout) return new Map()
  const state = scene.lastApplied
  if (state && state.roundStage === 'SHOWDOWN') {
    const hands = new Map<number, PlayerView['bestHand']>()
    for (const p of state.players) if (p.bestHand) hands.set(p.id, p.bestHand)
    return hands
  }
  return scene.retainedShowdownHands
}

/** Pocket cards to outline in the best-hand row: live at showdown, else retained. */
function currentPocketCards(scene: SceneState): Map<number, string[]> {
  const state = scene.lastApplied
  if (state && state.roundStage === 'SHOWDOWN') {
    const pockets = new Map<number, string[]>()
    for (const p of state.players) if (p.pocketCards) pockets.set(p.id, p.pocketCards)
    return pockets
  }
  return scene.retainedPocketCards
}

/** Re-render seats from the latest applied snapshot (used after animations settle). */
export function refreshSeats(scene: SceneState) {
  if (!scene.lastApplied) return
  updateSeats(scene, scene.lastApplied, scene.myPlayerId, scene.maxPlayers,
    currentShowdownHands(scene), currentPocketCards(scene), scene.winnerPlayerIds)
}
