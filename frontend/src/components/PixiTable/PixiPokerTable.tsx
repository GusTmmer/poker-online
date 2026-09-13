import { useEffect, useRef } from 'react'
import { Application, Container, Ticker } from 'pixi.js'
import type { GameStateUpdate } from '../../api/types'
import type { GameEvent } from '../../game/events'
import type { Frame, FrameBus } from '../../game/frameBus'
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
import { spawnActionBurst } from './burst'
import { tween, cancelTweens } from './tween'
import { getSlotMap, refreshSeats, seatPos } from './seats'
import { tick } from './tick'

// Module map: scene types → ./sceneTypes · constants → ./constants · pot pyramid →
// ./pot · chip sprites → ./chips · tween engine → ./tween · seat rendering →
// ./seats · per-frame loop → ./tick · table rendering → ./drawTable. This file
// owns Pixi setup, the community/deal animation setup, and the apply-snapshot /
// handle-event dispatch.

// ─── component ────────────────────────────────────────────────────────────────
interface Props {
  bus: FrameBus
  myPlayerId: number
  maxPlayers: number
  /** Told when an all-in runout starts and finishes, so lobby controls can wait for the reveal. */
  onRunoutChange?: (playing: boolean) => void
}

export function PixiPokerTable({ bus, myPlayerId, maxPlayers, onRunoutChange }: Props) {
  const containerRef   = useRef<HTMLDivElement>(null)
  const sceneRef       = useRef<SceneState | null>(null)
  const myPlayerIdRef  = useRef<number>(myPlayerId)
  const maxPlayersRef  = useRef<number>(maxPlayers)
  const cleanupRef     = useRef<(() => void) | null>(null)
  const onRunoutChangeRef = useRef(onRunoutChange)

  // Keep identity refs in sync so the Pixi init callback (created once) and the
  // bus subscription always read current values. Done in an effect — never during
  // render — so React's concurrency guarantees hold.
  useEffect(() => {
    myPlayerIdRef.current = myPlayerId
    maxPlayersRef.current = maxPlayers
    onRunoutChangeRef.current = onRunoutChange
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
        tweens: [], bursts: [], runout: null, dealEndsAt: 0,
        ticker, isDealing: false, isCommunityDealing: false,
        lastApplied: null, myPlayerId: myPlayerIdRef.current, maxPlayers: maxPlayersRef.current,
        retainedShowdownHands: new Map(), retainedPocketCards: new Map(),
        shownCommunity: [], winnerPlayerIds: new Set(),
        myCardsPocketKey: '', lastPotTotal: -1, lastRoundStage: undefined,
        potVariant: randomPotVariant(),
      }
      sceneRef.current = scene

      const onResize = () => doResize(container, canvas, app.renderer)
      onResize()
      // The container follows the (possibly rotated) game stage; the control bar changes size with
      // its mode and layout. Either one resizing re-fits the table.
      const resizeObserver = new ResizeObserver(onResize)
      resizeObserver.observe(container)
      const bar = container.parentElement?.querySelector('[data-testid="control-bar"]')
      if (bar) resizeObserver.observe(bar)
      window.addEventListener('resize', onResize)
      // Moving the window to a display with a different pixel density fires no resize.
      let dprQuery: MediaQueryList | null = null
      const watchDpr = () => {
        dprQuery?.removeEventListener('change', onDprChange)
        dprQuery = window.matchMedia(`(resolution: ${window.devicePixelRatio}dppx)`)
        dprQuery.addEventListener('change', onDprChange)
      }
      const onDprChange = () => { onResize(); watchDpr() }
      watchDpr()

      // Drive the canvas off the frame bus: apply the snapshot statically, then
      // play any derived events. subscribe() immediately replays the last frame
      // as cold, covering snapshots that arrived while Pixi was initializing.
      const unsubscribe = bus.subscribe((frame) => {
        const s = sceneRef.current
        if (!s) return
        s.myPlayerId = myPlayerIdRef.current
        s.maxPlayers = maxPlayersRef.current
        processFrame(s, frame, (playing) => onRunoutChangeRef.current?.(playing))
      })

      cleanupRef.current = () => {
        unsubscribe()
        if (scene.runout) scene.runout.timers.forEach(clearTimeout)
        window.removeEventListener('resize', onResize)
        resizeObserver.disconnect()
        dprQuery?.removeEventListener('change', onDprChange)
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
// Fits the table into the part of the game stage the control bar leaves free: above a
// bottom bar, or left of the side column on short (phone landscape) screens.
const DEFAULT_BAR_HEIGHT = 88
// On short screens the logical canvas' empty margins are cropped: fit this much of the
// scene (seats, labels and showdown rows) rather than the full 800×560.
const COMPACT_CONTENT_W = 740
const COMPACT_CONTENT_H = 500

function doResize(
  container: HTMLElement,
  canvas: HTMLCanvasElement,
  renderer: { resize: (w: number, h: number, r: number) => void },
) {
  // Layout sizes (offsetWidth & co.) are pre-transform, so they're right inside a rotated stage too.
  const stageW = container.clientWidth || window.innerWidth
  const stageH = container.clientHeight || window.innerHeight
  const bar = container.parentElement?.querySelector<HTMLElement>('[data-testid="control-bar"]')
  const sideBar = bar != null && bar.offsetHeight >= stageH * 0.9
  const pw = sideBar ? stageW - bar.offsetWidth : stageW
  const ph = sideBar ? stageH : stageH - (bar?.offsetHeight ?? DEFAULT_BAR_HEIGHT)

  const compact = sideBar || ph < LH * 0.8
  const scale = compact
    ? Math.min(pw / COMPACT_CONTENT_W, ph / COMPACT_CONTENT_H)
    : Math.min(pw / LW, ph / LH)
  // Crispness: the backing store must map 1:1 onto device pixels. Pick a whole number of device
  // pixels for the width, derive the render resolution from it, and size/place the canvas in
  // exact device-pixel units — a fractional CSS size or offset makes the browser resample the
  // whole bitmap, which reads as a soft, low-res table.
  const dpr = window.devicePixelRatio || 1
  const deviceW = Math.max(1, Math.round(LW * scale * dpr))
  const resolution = deviceW / LW
  const deviceH = Math.round(LH * resolution)
  renderer.resize(LW, LH, resolution)
  const snap = (cssPx: number) => Math.round(cssPx * dpr) / dpr
  const w = deviceW / dpr, h = deviceH / dpr
  // Keep the scene centre (LH/2 - SCENE_Y_OFFSET logical) in the middle of the free area; a cropped
  // canvas simply overflows the stage edges.
  const top = compact ? ph / 2 - (LH / 2 - SCENE_Y_OFFSET / 2) * (h / LH) : 0
  canvas.style.left   = `${snap((pw - w) / 2)}px`
  canvas.style.top    = `${snap(top)}px`
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
  scene.dealEndsAt = performance.now() + crossfadeAt
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

// ─── frame dispatch + all-in runout ─────────────────────────────────────────────
// A call that puts the last chips in lands as ONE frame carrying the rest of the board,
// the showdown and the payouts. Played as-is the hand is over before anyone sees the
// cards come, so that frame is staged instead: the players' pocket cards are tabled,
// then flop → turn → river are dealt with a beat after each, and only then are the
// best hands, winners and payouts revealed. Frames arriving meanwhile (the round
// clearing, the next hand) queue and replay in order afterwards.

const RUNOUT_LEAD_MS = 700    // tabled cards on display before the first street
const RUNOUT_PAUSE_MS = 1000  // beat after each street lands
const STREET_ENDS = [3, 4, 5]

/** Kinds held back until the board is out: they reveal the result. */
const RESULT_EVENTS = new Set<GameEvent['kind']>(['community_revealed', 'showdown', 'pot_awarded', 'chips_changed'])

type RunoutListener = (playing: boolean) => void

function processFrame(scene: SceneState, frame: Frame, onRunout: RunoutListener) {
  if (scene.runout) {
    if (!frame.cold) { scene.runout.queued.push(frame); return }
    // A reconnect snapshot supersedes the staged reveal.
    abortRunout(scene, onRunout)
  }

  const streets = frame.cold ? [] : runoutStreets(scene, frame.state)
  if (streets.length > 0) {
    startRunout(scene, frame, streets, onRunout)
    return
  }

  applySnapshot(scene, frame.state, frame.cold)
  if (!frame.cold) for (const event of frame.events) handleEvent(scene, event)
}

/** Street boundaries still to deal when [state] jumps to showdown with undealt board cards. */
function runoutStreets(scene: SceneState, state: GameStateUpdate): number[] {
  if (state.roundStage !== 'SHOWDOWN' || scene.lastApplied?.roundStage === 'SHOWDOWN') return []
  const shown = scene.shownCommunity.length
  if (state.communityCards.length <= shown) return []
  const contenders = state.players.filter((p) => p.isActive && p.pocketCards).length
  if (contenders < 2) return []
  return STREET_ENDS.filter((end) => end > shown && end <= state.communityCards.length)
}

function streetDealMs(cardCount: number) {
  return DECK_DELAY_MS + SLIDE_MS + FLIP_PAUSE_MS + (cardCount - 1) * FLIP_STAGGER_MS + FLIP_HALF_MS * 2
}

function startRunout(scene: SceneState, frame: Frame, streets: number[], onRunout: RunoutListener) {
  const prev = scene.lastApplied
  const runout = { final: frame, queued: [] as Frame[], timers: [] as ReturnType<typeof setTimeout>[] }
  scene.runout = runout
  onRunout(true)

  applySnapshot(scene, maskRunoutState(prev, frame.state, scene.shownCommunity), false)
  for (const event of frame.events) if (!RESULT_EVENTS.has(event.kind)) handleEvent(scene, event)

  const cards = frame.state.communityCards
  let at = Math.max(RUNOUT_LEAD_MS, scene.isDealing ? scene.dealEndsAt - performance.now() + 400 : 0)
  let dealt = scene.shownCommunity.length
  for (const end of streets) {
    const count = end - dealt
    runout.timers.push(setTimeout(() => renderCommunity(scene, cards.slice(0, end), scene.shownCommunity), at))
    at += streetDealMs(count) + RUNOUT_PAUSE_MS
    dealt = end
  }
  runout.timers.push(setTimeout(() => finishRunout(scene, onRunout), at))
}

function finishRunout(scene: SceneState, onRunout: RunoutListener) {
  const runout = scene.runout
  if (!runout) return
  scene.runout = null

  applySnapshot(scene, runout.final.state, false)
  for (const event of runout.final.events) {
    if (RESULT_EVENTS.has(event.kind) && event.kind !== 'community_revealed') handleEvent(scene, event)
  }
  onRunout(false)
  for (const frame of runout.queued) processFrame(scene, frame, onRunout)
}

function abortRunout(scene: SceneState, onRunout: RunoutListener) {
  scene.runout?.timers.forEach(clearTimeout)
  scene.runout = null
  onRunout(false)
}

/**
 * The showdown frame with its spoilers removed: the board as dealt so far, no best
 * hands, and stacks as they stood before the payout (a stack that grew shows its
 * pre-showdown size; the player whose call ended the betting shows it minus the call).
 */
function maskRunoutState(prev: GameStateUpdate | null, next: GameStateUpdate, shownCommunity: string[]): GameStateUpdate {
  const called = prev ? Math.max(0, next.potTotal - prev.potTotal) : 0
  const caller = prev?.nextPlayerIdToAct ?? null
  return {
    ...next,
    communityCards: shownCommunity,
    nextPlayerIdToAct: null,
    players: next.players.map((p) => {
      const before = prev?.players.find((q) => q.id === p.id)
      if (!before) return { ...p, bestHand: null }
      const preShowdown = p.id === caller ? Math.max(0, before.chips - called) : before.chips
      return { ...p, chips: Math.min(preShowdown, p.chips), bestHand: null }
    }),
  }
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
  // A finished showdown keeps its board on the felt beside the retained hands until
  // the next deal ('round_started' clears it).
  if (state.communityCards.length === 0) {
    const keepShowdownBoard = !cold && state.roundStage == null && scene.retainedShowdownHands.size > 0
    if (scene.shownCommunity.length > 0 && !keepShowdownBoard) renderCommunity(scene, [], [])
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
      if (scene.shownCommunity.length > 0) renderCommunity(scene, [], [])
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

    case 'player_raised': {
      const pos = seatPos(scene, event.playerId)
      const verb = event.action === 'bet' ? 'Bet' : 'Raise'
      spawnActionBurst(scene, pos.x, pos.y, `${verb} ${event.to.toLocaleString('en-US')}`, false)
      break
    }

    case 'player_all_in': {
      const pos = seatPos(scene, event.playerId)
      spawnActionBurst(scene, pos.x, pos.y, 'ALL IN', true)
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
