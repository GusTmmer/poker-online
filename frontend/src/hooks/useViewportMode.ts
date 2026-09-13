import { useEffect, useState } from 'react'

export interface ViewportMode {
  /** A touch device held in portrait: the game screen is turned 90° so the table gets the long edge. */
  rotated: boolean
  /** The (effective, post-rotation) screen is short — phone landscape. Controls move to a side column. */
  compact: boolean
  /** Effective layout size in CSS px, after rotation. */
  width: number
  height: number
}

const COMPACT_MAX_HEIGHT = 520
const PHONE_MAX_SHORT_SIDE = 600

function read(): ViewportMode {
  const w = window.innerWidth
  const h = window.innerHeight
  const touch = window.matchMedia('(pointer: coarse)').matches
  // Phones only: a portrait tablet has room for the table as-is.
  const rotated = touch && h > w && w < PHONE_MAX_SHORT_SIDE
  const width = rotated ? h : w
  const height = rotated ? w : h
  return { rotated, compact: height <= COMPACT_MAX_HEIGHT, width, height }
}

/** Tracks whether the game screen should be rotated to landscape and whether its layout is compact. */
export function useViewportMode(): ViewportMode {
  const [mode, setMode] = useState(read)

  useEffect(() => {
    const update = () =>
      setMode((prev) => {
        const next = read()
        return prev.rotated === next.rotated && prev.compact === next.compact &&
          prev.width === next.width && prev.height === next.height ? prev : next
      })
    window.addEventListener('resize', update)
    window.addEventListener('orientationchange', update)
    window.visualViewport?.addEventListener('resize', update)
    return () => {
      window.removeEventListener('resize', update)
      window.removeEventListener('orientationchange', update)
      window.visualViewport?.removeEventListener('resize', update)
    }
  }, [])

  return mode
}
