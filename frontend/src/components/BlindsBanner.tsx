import { useEffect, useRef, useState } from 'react'
import styled from '@emotion/styled'
import { AnimatePresence, motion } from 'framer-motion'
import { gradient, palette } from '../theme'

const Banner = styled(motion.div)`
  position: fixed;
  top: 1.25rem;
  left: 50%;
  z-index: 250;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 0.6rem;
  padding: 0.55rem 1.4rem;
  background: ${gradient.gold};
  color: ${palette.ink};
  font-family: 'Cinzel', Georgia, serif;
  font-weight: 700;
  font-size: 1rem;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  border: 1px solid ${palette.goldBright};
  border-radius: 8px;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.35),
    0 4px 16px rgba(0, 0, 0, 0.55);
  white-space: nowrap;
`

const Amount = styled.span`
  font-family: 'Courier New', ui-monospace, monospace;
  font-weight: 700;
`

const HIDE_AFTER_MS = 5000

/**
 * Announces a blind increase. Watches the small/big blind values across snapshots
 * and flashes a timed banner whenever either rises (whether from an auto-escalation
 * orbit or a passed INCREASE_BLINDS vote). A decrease — e.g. a new game resetting to
 * the base blinds — never triggers it.
 */
export function BlindsBanner({ small, big }: { small: number; big: number }) {
  const prev = useRef<{ small: number; big: number } | null>(null)
  const [announced, setAnnounced] = useState<{ small: number; big: number } | null>(null)

  useEffect(() => {
    const p = prev.current
    prev.current = { small, big }
    if (p && (big > p.big || small > p.small)) {
      setAnnounced({ small, big })
      const timer = setTimeout(() => setAnnounced(null), HIDE_AFTER_MS)
      return () => clearTimeout(timer)
    }
  }, [small, big])

  return (
    <AnimatePresence>
      {announced && (
        <Banner
          key={`${announced.small}-${announced.big}`}
          initial={{ y: -60, opacity: 0 }}
          animate={{ y: 0, opacity: 1 }}
          exit={{ y: -60, opacity: 0 }}
          transition={{ type: 'spring', stiffness: 320, damping: 26 }}
        >
          <span>Blinds up</span>
          <Amount>
            {announced.small} / {announced.big}
          </Amount>
        </Banner>
      )}
    </AnimatePresence>
  )
}
