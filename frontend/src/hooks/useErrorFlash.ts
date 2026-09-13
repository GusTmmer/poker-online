import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../api/types'

/** A short-lived error flag plus the server's message when the failure carried one. */
export function useErrorFlash(durationMs = 3000) {
  const [failed, setFailed] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current)
    }
  }, [])

  const showError = useCallback(
    (error?: unknown) => {
      if (timerRef.current) clearTimeout(timerRef.current)
      setFailed(true)
      setMessage(error instanceof ApiError && error.message ? error.message : null)
      timerRef.current = setTimeout(() => {
        setFailed(false)
        setMessage(null)
      }, durationMs)
    },
    [durationMs],
  )

  return { failed, message, showError }
}
