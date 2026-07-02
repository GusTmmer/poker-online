import { Graphics } from 'pixi.js'
import type { GameStateUpdate, PlayerView } from '../../api/types'
import { hex } from '../../theme'
import { SEAT_STADIUM, CARD_STADIUM, slotPosition } from './layout'
import { buildSeat, updateSeat } from './drawSeat'
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
  winnerPlayerIds: Set<number>,
) {
  const { players, roundStage, nextPlayerIdToAct, readyPlayerIds } = gameState
  const roundInProgress = roundStage != null
  const slotMap  = getSlotMap(players)
  const mySlot   = slotMap.get(myPlayerId) ?? 0
  const seatCount = Math.max(maxPlayers, players.length, 2)
  const occupied  = new Set<number>()

  for (const player of players) {
    const slot = slotMap.get(player.id)!
    occupied.add(slot)
    const isMe   = player.id === myPlayerId
    const seatPosition = slotPosition(slot, mySlot, seatCount, SEAT_STADIUM)

    const flipLabels = seatPosition.y < -10

    let entry = scene.seats.get(player.id)
    if (!entry) {
      const seatObj = buildSeat(player, isMe, flipLabels)
      seatObj.root.position.set(seatPosition.x, seatPosition.y)
      scene.seatsLayer.addChild(seatObj.root)

      const miniCards = makeMiniCardBackPair()
      miniCards.pivot.set(PAIR_W / 2, CARD_H / 2)
      miniCards.scale.set(MINI_SCALE)
      const cardPos = slotPosition(slot, mySlot, seatCount, CARD_STADIUM)
      miniCards.position.set(cardPos.x, cardPos.y)
      miniCards.rotation = (cardPos.angleDeg - 90) * Math.PI / 180
      miniCards.visible = false
      scene.miniCardsLayer.addChild(miniCards)

      entry = { slot, playerId: player.id, seatObj, miniCards, miniCardsVisible: false }
      scene.seats.set(player.id, entry)
    }

    const isNextToAct = !scene.isDealing && !scene.isCommunityDealing && player.id === nextPlayerIdToAct
    const isWinner    = winnerPlayerIds.has(player.id)

    updateSeat(
      entry.seatObj, player, isMe, isNextToAct, isWinner,
      readyPlayerIds.includes(player.id), roundInProgress,
      showdownHands.get(player.id), flipLabels,
    )

    // Hide mini card backs at showdown — the hand section already renders the cards.
    const showMini = !scene.isDealing && roundInProgress && player.isActive && player.chips > 0 && roundStage !== 'SHOWDOWN'
    if (showMini !== entry.miniCardsVisible) {
      entry.miniCardsVisible = showMini
      if (showMini) {
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
      scene.seats.delete(id)
    }
  }

  renderEmptySeats(scene, seatCount, mySlot, occupied)
  updateMyCards(scene, gameState, myPlayerId, mySlot, seatCount)
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

  const pair = me.pocketCards ? makeCardFacePair(me.pocketCards) : makeCardBackPair()
  pair.pivot.set(PAIR_W / 2, CARD_H / 2)
  pair.scale.set(MY_CARD_SCALE)
  myCardsContainer.addChild(pair)

  myCardsContainer.alpha = 0
  tween(scene, myCardsContainer, { alpha: 1 }, 400)
}

// ─── showdown hands + refresh ─────────────────────────────────────────────────
/** Best hands to show on seats: live at showdown, else whatever we retained from one. */
function currentShowdownHands(scene: SceneState): Map<number, PlayerView['bestHand']> {
  const state = scene.lastApplied
  if (state && state.roundStage === 'SHOWDOWN') {
    const hands = new Map<number, PlayerView['bestHand']>()
    for (const p of state.players) if (p.bestHand) hands.set(p.id, p.bestHand)
    return hands
  }
  return scene.retainedShowdownHands
}

/** Re-render seats from the latest applied snapshot (used after animations settle). */
export function refreshSeats(scene: SceneState) {
  if (!scene.lastApplied) return
  updateSeats(scene, scene.lastApplied, scene.myPlayerId, scene.maxPlayers,
    currentShowdownHands(scene), scene.winnerPlayerIds)
}
