import { useCallback, useEffect, useRef, useState } from 'react'

export function useErrorFlash(durationMs = 3000) {
  const [failed, setFailed] = useState(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current)
    }
  }, [])

  const showError = useCallback(() => {
    if (timerRef.current) clearTimeout(timerRef.current)
    setFailed(true)
    timerRef.current = setTimeout(() => setFailed(false), durationMs)
  }, [durationMs])

  return { failed, showError }
}
