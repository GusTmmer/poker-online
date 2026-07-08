import styled from '@emotion/styled'
import { useNavigate } from 'react-router-dom'
import { useSession } from '../context/SessionContext'
import { useGameSocket } from '../ws/useGameSocket'
import { useStateLogger } from '../hooks/useStateLogger'
import { PixiPokerTable } from '../components/PixiTable/PixiPokerTable'
import { ControlBar } from '../components/ControlBar/ControlBar'
import { VotePopupLayer } from '../components/VotePopup/VotePopupLayer'
import { Toasts } from '../components/Toasts'
import { gradient, palette } from '../theme'

const Status = styled.p`
  text-align: center;
  margin-top: 4rem;
`

const LeaveButton = styled.button`
  position: fixed;
  top: 1rem;
  left: 1rem;
  z-index: 210;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 2.4rem;
  height: 2.4rem;
  background: ${gradient.panel};
  color: ${palette.cream};
  font-size: 1.3rem;
  line-height: 1;
  border: 1px solid rgba(216, 182, 90, 0.55);
  border-radius: 50%;
  cursor: pointer;
  box-shadow:
    inset 0 1px 0 rgba(245, 217, 138, 0.12),
    0 2px 8px rgba(0, 0, 0, 0.5);
  transition: filter 0.15s, transform 0.15s;

  &:hover {
    filter: brightness(1.15);
    transform: translateY(-1px);
  }

  &:focus-visible {
    outline: 2px solid ${palette.goldBright};
    outline-offset: 2px;
  }
`

const Banner = styled.div<{ variant?: 'pause' | 'error' }>`
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  background: ${(p) => (p.variant === 'pause' ? palette.pauseBlue : palette.dangerBottom)};
  color: ${palette.cream};
  text-align: center;
  padding: 0.5rem 1rem;
  font-size: 0.9rem;
  letter-spacing: 0.02em;
  border-bottom: 1px solid rgba(216, 182, 90, 0.35);
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.4);
  z-index: 200;
`

export function GameScreen() {
  const navigate = useNavigate()
  const { tableId, myPlayerId, tableInfo } = useSession()
  const { gameState, bus, connectionState, toasts, dismissToast } = useGameSocket(tableId)
  useStateLogger(gameState)

  if (!gameState || myPlayerId === null || !tableInfo) {
    return <Status>Connecting to table {tableId}… ({connectionState})</Status>
  }

  return (
    <>
      <LeaveButton data-testid="btn-leave" aria-label="Leave table" title="Leave" onClick={() => navigate('/')}>
        <span aria-hidden>↩</span>
      </LeaveButton>
      <PixiPokerTable bus={bus} myPlayerId={myPlayerId} maxPlayers={tableInfo.maxPlayers} />
      <ControlBar gameState={gameState} myPlayerId={myPlayerId} tableId={tableId} />
      <VotePopupLayer gameState={gameState} tableId={tableId} />
      <Toasts toasts={toasts} onDismiss={dismissToast} />
      {gameState.gameStatus === 'PAUSED' && (
        <Banner variant="pause">Game paused — too many players went idle</Banner>
      )}
      {connectionState === 'closed' && (
        <Banner variant="pause">Connection dropped — reconnecting…</Banner>
      )}
      {connectionState === 'abandoned' && (
        <Banner variant="error">Connection lost — please reload the page to reconnect</Banner>
      )}
    </>
  )
}
