import { useEffect, useRef } from 'react'
import { Application, Container, Ticker } from 'pixi.js'
import type { GameStateUpdate } from '../../api/types'
import type { GameEvent } from '../../game/events'
import type { FrameBus } from '../../game/frameBus'
import {
  LW, LH, SCENE_Y_OFFSET,
  CARD_STADIUM,
  slotPosition,
} from './layout'
import { drawTable } from './drawTable'
import {
  makeCardBack, makeCardFace, makeDeck, makeMiniCardBack,
  CARD_W, CARD_H,
} from './drawCard'
import {
  DECK_DELAY_MS, SLIDE_MS, FLIP_PAUSE_MS, FLIP_STAGGER_MS, FLIP_HALF_MS, DECK_LINGER_MS,
  MINI_SCALE, DEAL_INTERVAL_MS, DEAL_FLIGHT_MS, HALF_SPACING, FOLD_MUCK_MS,
  COMM_SCALE, COMM_GAP, commRowW,
} from './constants'
import type { SceneState } from './sceneTypes'
import { randomPotVariant, updatePot } from './pot'
import { spawnDelta, spawnFlyingChip, spawnWinnerChips } from './chips'
import { tween, cancelTweens } from './tween'
import { getSlotMap, refreshSeats, seatPos } from './seats'
import { tick } from './tick'

// Module map: scene types → ./sceneTypes · constants → ./constants · pot pyramid →
// ./pot · chip sprites → ./chips · tween engine → ./tween · seat rendering →
// ./seats · per-frame loop → ./tick · table rendering → ./drawTable. This file
// owns Pixi setup, the community/deal animation setup, and the apply-snapshot /
// handle-event dispatch.

// ─── component ────────────────────────────────────────────────────────────────
interface Props { bus: FrameBus; myPlayerId: number; maxPlayers: number }

export function PixiPokerTable({ bus, myPlayerId, maxPlayers }: Props) {
  const containerRef   = useRef<HTMLDivElement>(null)
  const sceneRef       = useRef<SceneState | null>(null)
  const myPlayerIdRef  = useRef<number>(myPlayerId)
  const maxPlayersRef  = useRef<number>(maxPlayers)
  const cleanupRef     = useRef<(() => void) | null>(null)

  // Keep identity refs in sync so the Pixi init callback (created once) and the
  // bus subscription always read current values. Done in an effect — never during
  // render — so React's concurrency guarantees hold.
  useEffect(() => {
    myPlayerIdRef.current = myPlayerId
    maxPlayersRef.current = maxPlayers
  })

  useEffect(() => {
    const container = containerRef.current
    if (!container) return

    // Let Pixi create its own canvas so each init gets a fresh WebGL context.
    // Passing a shared React canvas causes context-lost errors in StrictMode because
    // both the first and second effect invocations call app.init() concurrently on
    // the same canvas element.
    const app = new Application()
    let destroyed = false

    // Canvas text (names, pot plaque) is rasterized once at creation, so the
    // display font must be resolved before the first frame renders. Race a
    // short timeout so a failed webfont load degrades to Georgia, not a hang.
    const fontsReady = Promise.race([
      Promise.all([
        document.fonts.load('600 14px Cinzel'),
        document.fonts.load('700 14px Cinzel'),
      ]).catch(() => undefined),
      new Promise((resolve) => setTimeout(resolve, 1200)),
    ])

    Promise.all([
      app.init({
        width: LW,
        height: LH,
        resolution: window.devicePixelRatio || 1,
        autoDensity: true,
        backgroundAlpha: 0,
        antialias: true,
      }),
      fontsReady,
    ]).then(() => {
      if (destroyed) { app.destroy(); return }

      const canvas = app.canvas as HTMLCanvasElement
      canvas.style.display  = 'block'
      canvas.style.position = 'fixed'
      canvas.style.top      = '0'
      canvas.style.left     = '0'
      container.appendChild(canvas)

      const root = new Container()
      root.position.set(LW / 2, LH / 2 - SCENE_Y_OFFSET)
      app.stage.addChild(root)

      root.addChild(drawTable())

      const emptySeatsLayer = new Container()
      const seatsLayer      = new Container()
      const miniCardsLayer  = new Container()
      const potContainer    = new Container()
      const communityRow    = new Container()
      const myCardsContainer= new Container()
      const animLayer       = new Container()
      root.addChild(emptySeatsLayer, seatsLayer, miniCardsLayer, potContainer, communityRow, myCardsContainer, animLayer)

      const ticker = new Ticker()
      ticker.add((t) => { if (sceneRef.current) tick(sceneRef.current, t.deltaMS) })
      ticker.start()

      const scene: SceneState = {
        app, root,
        seatsLayer, emptySeatsLayer, miniCardsLayer,
        communityRow, potContainer, myCardsContainer, animLayer,
        seats: new Map(), commCards: [], commDeck: null,
        flyingChips: [], winnerChips: [], floatingDeltas: [], dealCards: [],
        tweens: [],
        ticker, isDealing: false, isCommunityDealing: false,
        lastApplied: null, myPlayerId: myPlayerIdRef.current, maxPlayers: maxPlayersRef.current,
        retainedShowdownHands: new Map(), retainedPocketCards: new Map(),
        shownCommunity: [], winnerPlayerIds: new Set(),
        myCardsPocketKey: '', lastPotTotal: -1, lastRoundStage: undefined,
        potVariant: randomPotVariant(),
      }
      sceneRef.current = scene

      doResize(canvas, app.renderer)
      const onResize = () => doResize(canvas, app.renderer)
      window.addEventListener('resize', onResize)

      // Drive the canvas off the frame bus: apply the snapshot statically, then
      // play any derived events. subscribe() immediately replays the last frame
      // as cold, covering snapshots that arrived while Pixi was initializing.
      const unsubscribe = bus.subscribe((frame) => {
        const s = sceneRef.current
        if (!s) return
        s.myPlayerId = myPlayerIdRef.current
        s.maxPlayers = maxPlayersRef.current
        applySnapshot(s, frame.state, frame.cold)
        if (!frame.cold) for (const event of frame.events) handleEvent(s, event)
      })

      cleanupRef.current = () => {
        unsubscribe()
        window.removeEventListener('resize', onResize)
        ticker.stop(); ticker.destroy()
        canvas.remove()
        app.destroy(false)
      }
    }).catch((err: unknown) => {
      console.error('[PixiPokerTable] Pixi init failed:', err)
    })

    return () => {
      destroyed = true
      cleanupRef.current?.()
      cleanupRef.current = null
      sceneRef.current = null
    }
  }, [bus])

  return (
    <div
      ref={containerRef}
      style={{ position: 'fixed', inset: 0 }}
    />
  )
}

// ─── resize ───────────────────────────────────────────────────────────────────
// Approximate height of the fixed ControlBar so the canvas centres in the
// remaining drawable area rather than the full viewport.
const BOTTOM_UI_HEIGHT = 88 // px — update if bar height changes

function doResize(
  canvas: HTMLCanvasElement,
  renderer: { resize: (w: number, h: number, r: number) => void },
) {
  const pw  = window.innerWidth
  const ph  = window.innerHeight - BOTTOM_UI_HEIGHT
  const dpr = window.devicePixelRatio || 1
  const scale = Math.min(pw / LW, ph / LH)
  const w = LW * scale, h = LH * scale
  // Set physical pixel count = CSS pixels × devicePixelRatio for a crisp render
  renderer.resize(LW, LH, dpr * scale)
  canvas.style.left   = `${(pw - w) / 2}px`
  canvas.style.top    = '0'
  canvas.style.width  = `${w}px`
  canvas.style.height = `${h}px`
}

// ─── community cards ──────────────────────────────────────────────────────────
/**
 * Render the community row for `cards`, treating the first `prevCards.length`
 * as already face-up (no animation) and dealing/flipping the remainder.
 * Pass `prevCards === cards` to render the whole row instantly (cold/reconnect),
 * or `prevCards = []` with cards to deal the full row.
 */
function renderCommunity(scene: SceneState, cards: string[], prevCards: string[]) {
  scene.communityRow.removeChildren()
  scene.commCards = []
  scene.commDeck = null
  scene.communityRow.scale.set(COMM_SCALE)
  scene.communityRow.position.set(0, -CARD_H / 2 - 8)
  scene.shownCommunity = cards

  if (cards.length === 0) { scene.isCommunityDealing = false; return }

  const total    = cards.length
  const rowW     = commRowW(total)
  const baseX    = -rowW / 2

  // Render pre-existing cards (already face-up, no animation)
  for (let i = 0; i < prevCards.length; i++) {
    const face = makeCardFace(prevCards[i])
    face.x = baseX + i * (CARD_W + COMM_GAP)
    scene.communityRow.addChild(face)
  }

  const newCards    = cards.slice(prevCards.length)
  if (newCards.length === 0) { scene.isCommunityDealing = false; return }

  // Deck sprite
  const deckX = baseX - 56
  const deckY = 52
  const deckSprite = makeDeck()
  deckSprite.pivot.set(CARD_W / 2, CARD_H / 2)
  deckSprite.rotation = 18 * Math.PI / 180
  deckSprite.position.set(deckX, deckY)
  deckSprite.alpha = 0
  scene.communityRow.addChild(deckSprite)
  const hideAt = DECK_DELAY_MS + SLIDE_MS + DECK_LINGER_MS
  scene.commDeck = { sprite: deckSprite, elapsed: 0, hideAt }

  // New cards start at deck position, slide to their final spot, then flip
  newCards.forEach((cardCode, batchIndex) => {
    const finalIndex  = prevCards.length + batchIndex
    const targetX     = baseX + finalIndex * (CARD_W + COMM_GAP)

    const outerContainer = new Container()
    outerContainer.position.set(deckX, deckY)
    outerContainer.alpha = 0

    const innerContainer = new Container()
    innerContainer.pivot.set(CARD_W / 2, CARD_H / 2)
    innerContainer.position.set(CARD_W / 2, CARD_H / 2)
    innerContainer.addChild(makeCardBack())
    outerContainer.addChild(innerContainer)
    scene.communityRow.addChild(outerContainer)

    scene.commCards.push({
      outerContainer, innerContainer, cardCode, batchIndex,
      elapsed: 0, phase: 'pre',
      startX: deckX, startY: deckY, targetX,
    })
  })

  // Track isCommunityDealing until last flip completes
  const totalMs = DECK_DELAY_MS + SLIDE_MS + FLIP_PAUSE_MS +
    (newCards.length - 1) * FLIP_STAGGER_MS + FLIP_HALF_MS * 2 + 100
  scene.isCommunityDealing = true
  setTimeout(() => {
    scene.isCommunityDealing = false
    // Re-evaluate seats now that the deal is over — turn halos are suppressed
    // while community cards animate and must reappear once they settle.
    refreshSeats(scene)
  }, totalMs)
}

// ─── deal animation ───────────────────────────────────────────────────────────
function startDeal(scene: SceneState) {
  const gameState = scene.lastApplied
  if (!gameState) return
  const myPlayerId = scene.myPlayerId
  const maxPlayers = scene.maxPlayers
  const slotMap   = getSlotMap(gameState.players)
  const mySlot    = slotMap.get(myPlayerId) ?? 0
  const seatCount = Math.max(maxPlayers, gameState.players.length, 2)

  let sbSlot = 0
  const sb = gameState.players.find((p) => p.isSmallBlind)
  if (sb) sbSlot = slotMap.get(sb.id) ?? 0

  const ordered: number[] = []
  for (let i = 0; i < seatCount; i++) {
    const slot = (sbSlot + i) % seatCount
    const p = gameState.players.find((pl) => (slotMap.get(pl.id) ?? -1) === slot)
    if (p?.isActive && p.chips > 0) ordered.push(slot)
  }
  if (ordered.length < 2) return

  scene.isDealing = true
  scene.myCardsPocketKey = ''  // force my cards to re-render after deal

  let idx = 0
  for (let round = 0; round < 2; round++) {
    for (const slot of ordered) {
      const cp   = slotPosition(slot, mySlot, seatCount, CARD_STADIUM)
      const rot  = (cp.angleDeg - 90) * Math.PI / 180
      const sign = round === 0 ? -1 : 1
      const toX  = cp.x + sign * HALF_SPACING * Math.cos(rot)
      const toY  = cp.y + sign * HALF_SPACING * Math.sin(rot)

      const sprite = makeMiniCardBack()
      sprite.pivot.set(CARD_W / 2, CARD_H / 2)
      sprite.scale.set(MINI_SCALE)
      sprite.rotation = rot
      sprite.alpha = 0
      scene.animLayer.addChild(sprite)

      scene.dealCards.push({ sprite, toX, toY, delay: idx * DEAL_INTERVAL_MS, elapsed: 0, done: false })
      idx++
    }
  }

  const crossfadeAt = (ordered.length * 2 - 1) * DEAL_INTERVAL_MS + DEAL_FLIGHT_MS + 250
  setTimeout(() => {
    for (const dc of scene.dealCards) {
      tween(scene, dc.sprite, { alpha: 0 }, 400, 0, () => scene.animLayer.removeChild(dc.sprite))
    }
    scene.isDealing = false
    scene.dealCards = []

    // Re-evaluate seats now that isDealing is false — the turn halo is suppressed
    // during the deal animation and must be shown as soon as it finishes.
    refreshSeats(scene)

    // Reveal mini-cards and my face-up cards — they were hidden during the deal
    // animation and won't show again until the next WS message otherwise.
    for (const [, entry] of scene.seats) {
      const player = gameState.players.find((p) => p.id === entry.playerId)
      if (player?.isActive && player.chips > 0 && gameState.roundStage != null) {
        entry.miniCardsVisible = true
        entry.miniCards.visible = true
        tween(scene, entry.miniCards, { alpha: 1 }, 300)
      }
    }
  }, crossfadeAt)
}

// ─── apply snapshot (idempotent steady-state) ───────────────────────────────────
// Renders "what things are": pot, community presence, seats, chip counts, halos.
// Re-applying the same snapshot is a no-op; safe on reconnect. No diffing here —
// transient motion is driven by handleEvent instead.
function applySnapshot(scene: SceneState, state: GameStateUpdate, cold: boolean) {
  scene.lastApplied = state

  updatePot(scene, state.potTotal, state.roundStage)

  // Community: clear on reset; render the whole row instantly on a cold frame
  // (reconnect / first paint). Warm additions arrive via 'community_revealed'.
  if (state.communityCards.length === 0) {
    if (scene.shownCommunity.length > 0) renderCommunity(scene, [], [])
  } else if (cold) {
    renderCommunity(scene, state.communityCards, state.communityCards)
  }

  refreshSeats(scene)
}

// ─── handle event (transient effects) ───────────────────────────────────────────
// The bus callback runs applySnapshot then every event synchronously; Pixi paints
// from its own ticker afterwards, so the final post-handler scene state is what
// shows — intermediate seat re-renders within one frame are never painted.
function handleEvent(scene: SceneState, event: GameEvent) {
  switch (event.kind) {
    case 'round_started':
      // Drop the previous hand's win presentation, then deal.
      scene.winnerPlayerIds.clear()
      scene.retainedShowdownHands.clear()
      scene.retainedPocketCards.clear()
      startDeal(scene)
      refreshSeats(scene)
      break

    case 'community_revealed':
      renderCommunity(scene, scene.lastApplied!.communityCards, scene.shownCommunity)
      break

    case 'player_bet': {
      const pos = seatPos(scene, event.playerId)
      spawnFlyingChip(scene, pos.x, pos.y)
      break
    }

    case 'chips_changed': {
      const pos = seatPos(scene, event.playerId)
      spawnDelta(scene, pos.x, pos.y, event.delta)
      break
    }

    case 'player_folded': {
      // Muck: glide the folder's mini cards to table center while fading. Take over
      // any in-flight fade that refreshSeats just started (it would otherwise hide
      // the cards mid-flight), and restore their home position/state on completion
      // so the next deal reveals them where they belong.
      const entry = scene.seats.get(event.playerId)
      if (!entry || !entry.miniCards.visible) break
      const cards = entry.miniCards
      const homeX = cards.x, homeY = cards.y
      cancelTweens(scene, cards)
      entry.miniCardsVisible = false
      tween(scene, cards, { x: 0, y: 0, alpha: 0 }, FOLD_MUCK_MS, 0, () => {
        if (!entry.miniCardsVisible) { cards.visible = false; cards.alpha = 0 }
        cards.position.set(homeX, homeY)
      })
      break
    }

    case 'showdown':
      scene.retainedShowdownHands = new Map(
        Object.entries(event.hands).map(([id, hand]) => [Number(id), hand]),
      )
      // Capture revealed pocket cards now (the snapshot still has them) so the
      // gold outline persists after the round clears and pocketCards go null.
      scene.retainedPocketCards = new Map(
        (scene.lastApplied?.players ?? [])
          .filter((p) => p.pocketCards)
          .map((p) => [p.id, p.pocketCards!]),
      )
      scene.winnerPlayerIds = new Set(event.winnerIds)
      refreshSeats(scene)  // green halos + hands on the same frame
      break

    case 'pot_awarded':
      // Fold wins skip showdown — seed winners from the award itself.
      if (scene.winnerPlayerIds.size === 0)
        scene.winnerPlayerIds = new Set(event.winners.map((w) => w.playerId))
      refreshSeats(scene)
      for (const w of event.winners) {
        const pos = seatPos(scene, w.playerId)
        spawnWinnerChips(scene, pos.x, pos.y)
      }
      break

    // Fully reflected by applySnapshot (turn halo via nextPlayerIdToAct, pot via
    // updatePot, fades via player status) — no separate transient effect today.
    case 'turn_changed':
    case 'pot_changed':
    case 'player_status_changed':
    case 'game_paused':
    case 'game_resumed':
      break
  }
}
