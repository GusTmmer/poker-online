import styled from '@emotion/styled'
import { useSession } from '../context/SessionContext'
import { useGameSocket } from '../ws/useGameSocket'
import { useStateLogger } from '../hooks/useStateLogger'
import { PixiPokerTable } from '../components/PixiTable/PixiPokerTable'
import { ControlBar } from '../components/ControlBar/ControlBar'
import { VotePopupLayer } from '../components/VotePopup/VotePopupLayer'
import { Toasts } from '../components/Toasts'
import { palette } from '../theme'

const Status = styled.p`
  text-align: center;
  margin-top: 4rem;
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
  z-index: 200;
`

export function GameScreen() {
  const { tableId, myPlayerId, tableInfo } = useSession()
  const { gameState, bus, connectionState, toasts, dismissToast } = useGameSocket(tableId)
  useStateLogger(gameState)

  if (!gameState || myPlayerId === null || !tableInfo) {
    return <Status>Connecting to table {tableId}… ({connectionState})</Status>
  }

  return (
    <>
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
