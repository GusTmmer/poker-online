import {useEffect, useRef, useState} from 'react'
import {type GameStateUpdate, isGameStateUpdate, normalizeGameState, type WsFrame} from '../api/types'
import {useToasts} from '../hooks/useToasts'
import {deriveEvents} from '../game/deriveEvents'
import {createDeriveContext} from '../game/events'
import {createFrameBus, type FrameBus} from '../game/frameBus'

export type {Toast} from '../hooks/useToasts'

export type ConnectionState = 'connecting' | 'open' | 'closed' | 'abandoned'

const RECONNECT_INITIAL_DELAY_MS = 1000
const RECONNECT_MAX_DELAY_MS = 30_000
const HEARTBEAT_INTERVAL_MS = 20_000
// After this many consecutive failed reconnects we give up and surface
// 'abandoned' so the UI can prompt a manual reload instead of looping forever.
const MAX_RECONNECT_ATTEMPTS = 10
// A backgrounded tab keeps its WebSocket open, which on Cloud Run keeps an instance
// (and its billing) alive. After the tab has been hidden this long we close the
// socket and reconnect when it's foregrounded again. Long enough that a quick
// tab-switch mid-hand doesn't disconnect (and risk an auto-fold), short enough that
// a forgotten tab stops costing money.
const HIDDEN_GRACE_MS = 60_000

export interface GameSocket {
    /** Latest snapshot, for React consumers (ControlBar, vote popups, banners). */
    gameState: GameStateUpdate | null
    /** Frame stream (snapshot + derived events), for the imperative Pixi renderer. */
    bus: FrameBus
    connectionState: ConnectionState
    toasts: ReturnType<typeof useToasts>['toasts']
    dismissToast: ReturnType<typeof useToasts>['dismissToast']
}

export function useGameSocket(tableId: number): GameSocket {
    const [gameState, setGameState] = useState<GameStateUpdate | null>(null)
    const [connectionState, setConnectionState] = useState<ConnectionState>('connecting')
    const {toasts, addToast, dismissToast} = useToasts()
    const reconnectDelayRef = useRef(RECONNECT_INITIAL_DELAY_MS)
    const reconnectAttemptsRef = useRef(0)

    // Event pipeline: a stable bus + cross-frame derivation context, plus the
    // previous snapshot. `prev = null` marks the next frame as cold (post-connect),
    // so it renders statically instead of replaying as animation.
    const [bus] = useState(createFrameBus)
    const deriveCtxRef = useRef(createDeriveContext())
    const prevSnapshotRef = useRef<GameStateUpdate | null>(null)

    useEffect(() => {
        let socket: WebSocket | null = null
        let reconnectTimer: ReturnType<typeof setTimeout> | null = null
        let heartbeatTimer: ReturnType<typeof setInterval> | null = null
        let hiddenTimer: ReturnType<typeof setTimeout> | null = null
        let cancelled = false
        // True while we've intentionally closed the socket because the tab is hidden,
        // so onclose must NOT schedule a reconnect — we reconnect on foreground instead.
        let suspendedForHidden = false

        function connect() {
            if (cancelled) return
            suspendedForHidden = false

            setConnectionState('connecting')
            const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
            // Capture this socket locally so its handlers can no-op once a newer socket
            // supersedes it (e.g. a foreground reconnect): a stale onclose must not fire
            // a second reconnect against the live connection.
            const ws = new WebSocket(`${protocol}//${window.location.host}/ws/tables/${tableId}`)
            socket = ws

            ws.onopen = () => {
                if (socket !== ws) return
                reconnectDelayRef.current = RECONNECT_INITIAL_DELAY_MS
                reconnectAttemptsRef.current = 0
                console.debug('[WS] connected', {tableId})
                setConnectionState('open')
                // The first snapshot after (re)connect must be treated as cold: discard
                // the pre-disconnect snapshot so no stale diff produces phantom animations.
                prevSnapshotRef.current = null
                // Send a lightweight ping every 20 seconds to keep the connection alive through
                // proxies that close idle WebSocket connections (Vite dev proxy, Cloud Run, etc.).
                heartbeatTimer = setInterval(() => {
                    if (ws.readyState === WebSocket.OPEN) {
                        ws.send(JSON.stringify({type: 'ping'}))
                    }
                }, HEARTBEAT_INTERVAL_MS)
            }

            ws.onmessage = (event) => {
                if (socket !== ws) return
                let frame: WsFrame
                try {
                    frame = JSON.parse(event.data) as WsFrame
                } catch {
                    return
                }

                if (isGameStateUpdate(frame)) {
                    const next = normalizeGameState(frame)
                    const prev = prevSnapshotRef.current
                    const cold = prev === null
                    // Derive events synchronously, per frame, before React renders — so a
                    // burst of snapshots never collapses (no flushSync needed) and the
                    // renderer always sees apply-then-events in order.
                    const events = deriveEvents(prev, next, deriveCtxRef.current)
                    prevSnapshotRef.current = next
                    bus.emit({state: next, events, cold})
                    setGameState(next)
                } else {
                    addToast(frame.type, frame.message)
                }
            }

            ws.onerror = (e) => {
                console.warn('[WS] error', e)
            }

            ws.onclose = (e) => {
                if (heartbeatTimer) clearInterval(heartbeatTimer)
                console.debug('[WS] closed', {code: e.code, reason: e.reason, wasClean: e.wasClean})
                // A superseded socket closing must not touch reconnect state for the live one.
                if (socket !== ws) return
                if (cancelled) return
                // Closed on purpose because the tab went idle — stay closed until it's
                // foregrounded (visibilitychange handles the reconnect), don't loop here.
                if (suspendedForHidden) {
                    setConnectionState('closed')
                    return
                }
                // Give up after too many consecutive failures rather than reconnecting
                // forever — the page is likely stale and needs a manual reload.
                if (reconnectAttemptsRef.current >= MAX_RECONNECT_ATTEMPTS) {
                    setConnectionState('abandoned')
                    return
                }
                reconnectAttemptsRef.current += 1
                setConnectionState('closed')
                reconnectTimer = setTimeout(connect, reconnectDelayRef.current)
                reconnectDelayRef.current = Math.min(reconnectDelayRef.current * 2, RECONNECT_MAX_DELAY_MS)
            }
        }

        function onVisibilityChange() {
            if (document.hidden) {
                // Start the grace countdown; if still hidden when it fires, drop the socket.
                if (hiddenTimer) clearTimeout(hiddenTimer)
                hiddenTimer = setTimeout(() => {
                    suspendedForHidden = true
                    if (reconnectTimer) clearTimeout(reconnectTimer)
                    socket?.close()
                }, HIDDEN_GRACE_MS)
            } else {
                if (hiddenTimer) clearTimeout(hiddenTimer)
                hiddenTimer = null
                // Foregrounded again — if we'd suspended (or are otherwise not open), reconnect now.
                if (suspendedForHidden || socket?.readyState !== WebSocket.OPEN) {
                    reconnectAttemptsRef.current = 0
                    reconnectDelayRef.current = RECONNECT_INITIAL_DELAY_MS
                    connect()
                }
            }
        }

        document.addEventListener('visibilitychange', onVisibilityChange)
        connect()

        return () => {
            cancelled = true
            document.removeEventListener('visibilitychange', onVisibilityChange)
            if (hiddenTimer) clearTimeout(hiddenTimer)
            if (heartbeatTimer) clearInterval(heartbeatTimer)
            if (reconnectTimer) clearTimeout(reconnectTimer)
            socket?.close()
        }
    }, [tableId, addToast, bus])

    return {gameState, bus, connectionState, toasts, dismissToast}
}
