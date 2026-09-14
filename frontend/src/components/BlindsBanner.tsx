import { useEffect, useRef, useState } from 'react'
import styled from '@emotion/styled'
import { AnimatePresence, motion } from 'framer-motion'
import { gradient, palette } from '../theme'

// Centering lives on a static flex wrapper, so framer-motion's transforms on the
// hero never fight a CSS translate. The whole layer ignores the pointer: the hand
// keeps playing underneath.
const Layer = styled.div`
  position: fixed;
  inset: 0;
  z-index: 250;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
`

// A soft pool of blur and shade behind the hero — feathered to nothing at its
// edges by the radial mask, so it draws the eye without reading as a modal.
const Halo = styled(motion.div)`
  position: absolute;
  width: min(560px, 96vw);
  height: min(320px, 70vh);
  background: radial-gradient(ellipse at center, rgba(8, 5, 3, 0.62) 0%, rgba(8, 5, 3, 0.35) 55%, transparent 72%);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  mask-image: radial-gradient(ellipse at center, #000 45%, transparent 72%);
  -webkit-mask-image: radial-gradient(ellipse at center, #000 45%, transparent 72%);
`

const Hero = styled(motion.div)`
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.35rem;
  padding: 1.1rem 2.4rem 1.2rem;
  background: ${gradient.panel};
  border: 1px solid ${palette.gold};
  border-radius: 12px;
  box-shadow:
    inset 0 0 0 4px rgba(23, 13, 9, 1),
    inset 0 0 0 5px rgba(216, 182, 90, 0.35),
    0 0 2.5rem rgba(216, 182, 90, 0.25),
    0 12px 32px rgba(0, 0, 0, 0.6);
  text-align: center;
`

const Title = styled.div`
  font-family: 'Cinzel', Georgia, serif;
  font-weight: 700;
  font-size: 0.95rem;
  letter-spacing: 0.3em;
  text-transform: uppercase;
  color: ${palette.gold};
`

const Amount = styled.div`
  font-family: 'Cinzel', Georgia, serif;
  font-weight: 700;
  font-size: clamp(2.2rem, 7vw, 3.2rem);
  line-height: 1.05;
  color: ${palette.goldBright};
  text-shadow: 0 0 1.2rem rgba(245, 217, 138, 0.35), 0 2px 3px rgba(0, 0, 0, 0.6);
  white-space: nowrap;
`

const HIDE_AFTER_MS = 2800

interface Announcement {
  small: number
  big: number
}

/**
 * Announces a blind increase as a centered hero over a blurred pool. Watches the
 * small/big blind values across snapshots and fires whenever either rises (an
 * auto-escalation orbit or a passed INCREASE_BLINDS vote). A decrease — e.g. a new
 * game resetting to the base blinds — never triggers it.
 */
export function BlindsBanner({ small, big }: { small: number; big: number }) {
  const prev = useRef<{ small: number; big: number } | null>(null)
  const [announced, setAnnounced] = useState<Announcement | null>(null)

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
        <Layer key={`${announced.small}-${announced.big}`} data-testid="blinds-hero" role="status">
          <Halo initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.3 }} />
          <Hero
            initial={{ scale: 0.6, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            exit={{ scale: 0.92, opacity: 0 }}
            transition={{ type: 'spring', stiffness: 380, damping: 22 }}
          >
            <Title>Blinds up</Title>
            <Amount>
              {announced.small} / {announced.big}
            </Amount>
          </Hero>
        </Layer>
      )}
    </AnimatePresence>
  )
}
