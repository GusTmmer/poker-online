import type {GameStateUpdate} from '../api/types'
import type {GameEvent} from './events'

/**
 * One processed game-state frame: the authoritative snapshot plus the semantic
 * events that occurred since the previous frame.
 */
export interface Frame {
    state: GameStateUpdate
    events: GameEvent[]
    /**
     * True for the first frame after a (re)connect. The renderer applies it
     * statically and skips animations — a cold snapshot describes "what is", not
     * "what just happened", so replaying it as motion would be wrong.
     */
    cold: boolean
}

export type FrameListener = (frame: Frame) => void

/**
 * A tiny synchronous pub/sub channel decoupling the socket layer from the Pixi
 * renderer. Driving the canvas off this bus (rather than a React prop) keeps
 * `applySnapshot`-then-`handleEvent` ordering deterministic and sidesteps React
 * batching — which is why the old `flushSync` hack is no longer needed.
 */
export interface FrameBus {
    /** Most recent frame, replayed to new subscribers so a late-mounting renderer catches up. */
    readonly last: Frame | null

    emit(frame: Frame): void

    /** Subscribe; immediately receives `last` (if any) as a cold frame. Returns an unsubscribe fn. */
    subscribe(listener: FrameListener): () => void
}

export function createFrameBus(): FrameBus {
    const listeners = new Set<FrameListener>()
    let last: Frame | null = null

    return {
        get last() {
            return last
        },
        emit(frame) {
            last = frame
            for (const listener of listeners) listener(frame)
        },
        subscribe(listener) {
            listeners.add(listener)
            if (last) listener({...last, cold: true})
            return () => {
                listeners.delete(listener)
            }
        },
    }
}
