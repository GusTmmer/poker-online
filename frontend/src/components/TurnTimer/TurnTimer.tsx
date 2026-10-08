import { useEffect, useState } from 'react'
import styled from '@emotion/styled'
import type { GameStateUpdate } from '../../api/types'
import { palette } from '../../theme'

const Wrap = styled.div<{ urgent: boolean }>`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.1rem;
  border: 1px solid ${(p) => (p.urgent ? palette.danger : 'rgba(216, 182, 90, 0.45)')};
  border-radius: 6px;
  padding: 0.3rem 0.6rem;
  min-width: 3.2rem;
  background: rgba(0, 0, 0, 0.35);
  box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.4);
  transition: border-color 0.3s;
`

const Seconds = styled.span<{ urgent: boolean }>`
  font-family: 'Courier New', monospace;
  font-size: 1.3rem;
  font-weight: bold;
  color: ${(p) => (p.urgent ? '#e05555' : palette.gold)};
  line-height: 1;
  transition: color 0.3s;
`

const Label = styled.span`
  font-family: 'Cinzel', Georgia, serif;
  font-size: 0.6rem;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: ${palette.parchment};
`

interface Props {
  gameState: GameStateUpdate
  myPlayerId: number
}

export function TurnTimer({ gameState, myPlayerId }: Props) {
  const endsAt = gameState.turnTimerEndsAt
  // Only the clock is state; the seconds shown are derived from it on every render.
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    if (!endsAt) return
    const tick = () => setNow(Date.now())
    // Catch up straight away: `now` stood still while no clock was running.
    const first = setTimeout(tick, 0)
    const id = setInterval(tick, 500)
    return () => { clearTimeout(first); clearInterval(id) }
  }, [endsAt])

  if (!endsAt || gameState.nextPlayerIdToAct == null) return null

  // The clock holds at the full turn while the table catches up (until turnClockStartsAt), then counts down.
  const startsAt = gameState.turnClockStartsAt ?? 0
  const secondsLeft = Math.max(0, Math.ceil((endsAt - Math.max(now, startsAt)) / 1000))

  const isMe = gameState.nextPlayerIdToAct === myPlayerId
  const urgent = secondsLeft <= 15
  const label = isMe ? 'Your turn' : 'Opponent'

  return (
    <Wrap data-testid="turn-timer" urgent={urgent}>
      <Seconds urgent={urgent}>{secondsLeft}</Seconds>
      <Label>{label}</Label>
    </Wrap>
  )
}
