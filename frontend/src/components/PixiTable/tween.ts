import type {SceneState} from './sceneTypes'

// ─── easing / math ───────────────────────────────────────────────────────────
export function easeOut(t: number) {
    return 1 - Math.pow(1 - t, 3)
}

export function easeIn(t: number) {
    return t * t * t
}

export function lerp(a: number, b: number, t: number) {
    return a + (b - a) * t
}

// ─── tween pool ───────────────────────────────────────────────────────────────
// A tiny tween engine over the shared scene. Targets are Pixi objects whose
// numeric props (alpha, x, y, …) are interpolated by string key.
export function tween(
    scene: SceneState, target: object, props: Record<string, number>,
    duration: number, delay = 0, onDone?: () => void,
) {
    const t = target as Record<string, number>
    const startProps: Record<string, number> = {}
    for (const k of Object.keys(props)) startProps[k] = t[k] ?? 0
    scene.tweens.push({target: t, props, startProps, elapsed: 0, duration, delay, ease: 'easeOut', onDone, done: false})
}

/** Drop any in-flight tweens targeting [target] (by identity) so a new tween can cleanly take over. */
export function cancelTweens(scene: SceneState, target: object) {
    scene.tweens = scene.tweens.filter((tw) => tw.target !== target)
}

export function tickTweens(scene: SceneState, dt: number) {
    for (const tw of scene.tweens) {
        if (tw.done) continue
        tw.elapsed += dt
        const t = Math.max(0, tw.elapsed - tw.delay)
        const p = Math.min(t / tw.duration, 1)
        const e = easeOut(p)
        for (const k of Object.keys(tw.props)) tw.target[k] = lerp(tw.startProps[k], tw.props[k], e)
        if (p >= 1) {
            tw.done = true;
            tw.onDone?.()
        }
    }
    scene.tweens = scene.tweens.filter((tw) => !tw.done)
}
