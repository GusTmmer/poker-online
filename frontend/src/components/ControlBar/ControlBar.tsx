import { useState, type ReactNode } from 'react'
import { useErrorFlash } from '../../hooks/useErrorFlash'
import styled from '@emotion/styled'
import type { ActionType, GameStateUpdate } from '../../api/types'
import { selectControls } from '../../game/selectors'
import { readyUp, sendAction, startRound, setPlayerOnline, restartGame } from '../../api/client'
import { VotingMenu } from './VotingMenu'
import { RAISE_STEP, snapRaiseValue } from './snapRaiseValue'
import { TurnTimer } from '../TurnTimer/TurnTimer'
import { gradient, palette } from '../../theme'

const Bar = styled.div`
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  background: ${gradient.panel};
  /* Double hairline: bright gold line over a dark seam, like an inlaid edge */
  border-top: 1px solid rgba(216, 182, 90, 0.55);
  box-shadow:
    0 -2px 0 rgba(0, 0, 0, 0.6),
    inset 0 1px 0 rgba(245, 217, 138, 0.12),
    0 -0.4rem 1.4rem rgba(0, 0, 0, 0.55);
  padding: 0.9rem 1rem;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 1rem;
  flex-wrap: wrap;
`

const Button = styled.button<{ variant?: 'danger' | 'primary'; fixedWidth?: boolean }>`
  padding: 0.75rem 1.5rem;
  border-radius: 6px;
  border: 1px solid ${palette.gold};
  font-family: 'Cinzel', Georgia, serif;
  font-weight: 600;
  font-size: 1rem;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  cursor: pointer;
  text-align: center;
  width: ${(p) => (p.fixedWidth ? '10rem' : 'auto')};
  background: ${(p) =>
    p.variant === 'danger'
      ? gradient.danger
      : p.variant === 'primary'
        ? gradient.gold
        : gradient.green};
  color: ${(p) => (p.variant === 'primary' ? palette.ink : palette.cream)};
  text-shadow: ${(p) => (p.variant === 'primary' ? 'none' : '0 1px 2px rgba(0, 0, 0, 0.5)')};
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.25),
    inset 0 -1px 0 rgba(0, 0, 0, 0.3),
    0 0.2rem 0.4rem rgba(0, 0, 0, 0.5);
  transition: filter 0.15s, transform 0.15s, box-shadow 0.15s;

  &:hover:not(:disabled) {
    filter: brightness(1.12);
    transform: translateY(-1px);
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.25),
      inset 0 -1px 0 rgba(0, 0, 0, 0.3),
      0 0.35rem 0.8rem rgba(0, 0, 0, 0.55);
  }

  &:active:not(:disabled) {
    filter: brightness(0.95);
    transform: translateY(0);
    box-shadow:
      inset 0 2px 3px rgba(0, 0, 0, 0.35),
      0 0.1rem 0.2rem rgba(0, 0, 0, 0.5);
  }

  &:focus-visible {
    outline: 2px solid ${palette.goldBright};
    outline-offset: 2px;
  }

  &:disabled {
    opacity: 0.4;
    cursor: not-allowed;
  }
`

const SliderWrap = styled.div`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.35rem;
  width: 11rem;
  color: ${palette.creamMuted};
  font-size: 0.9rem;
  flex-shrink: 0;
`

const RangeInput = styled('input', { shouldForwardProp: (p) => p !== 'fillPercent' })<{
  fillPercent: number
}>`
  -webkit-appearance: none;
  appearance: none;
  width: 100%;
  height: 4px;
  border-radius: 1px;
  outline: none;
  cursor: pointer;
  background: linear-gradient(
    to right,
    ${palette.gold} 0%,
    ${palette.goldMid} ${(p) => p.fillPercent}%,
    #0e1a12 ${(p) => p.fillPercent}%,
    #0e1a12 100%
  );
  border: 1px solid #3a2e10;
  box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.6);

  &::-webkit-slider-runnable-track {
    height: 4px;
    background: transparent;
  }

  &::-moz-range-track {
    height: 4px;
    background: transparent;
    border: none;
  }

  &::-webkit-slider-thumb {
    -webkit-appearance: none;
    width: 18px;
    height: 18px;
    margin-top: -8px;
    background: linear-gradient(135deg, #f5d98a 0%, #d8b65a 45%, #9c7a25 100%);
    border: 1px solid #c4972e;
    border-radius: 2px;
    transform: rotate(45deg);
    cursor: pointer;
    box-shadow:
      0 1px 4px rgba(0, 0, 0, 0.7),
      inset 0 1px 0 rgba(255, 255, 255, 0.3);
  }

  &::-moz-range-thumb {
    width: 18px;
    height: 18px;
    background: linear-gradient(135deg, #f5d98a 0%, #d8b65a 45%, #9c7a25 100%);
    border: 1px solid #c4972e;
    border-radius: 2px;
    transform: rotate(45deg);
    cursor: pointer;
    box-shadow:
      0 1px 4px rgba(0, 0, 0, 0.7),
      inset 0 1px 0 rgba(255, 255, 255, 0.3);
  }

  &:disabled {
    opacity: 0.4;
    cursor: not-allowed;
  }
`

const MonoValue = styled.span`
  font-family: 'Courier New', ui-monospace, monospace;
`

const CornerMenu = styled.div`
  position: absolute;
  right: 1rem;
  bottom: calc(100% + 0.5rem);
`

const RightSlot = styled.div`
  position: absolute;
  right: 1rem;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  align-items: center;
  gap: 0.5rem;
`

const ActionError = styled.span`
  color: ${palette.danger};
  font-size: 0.85rem;
  font-family: 'Cormorant Garamond', Georgia, serif;
`

interface ControlBarProps {
  gameState: GameStateUpdate
  myPlayerId: number
  tableId: number
}

/**
 * The persistent ControlBar chrome shared by every mode: the bar itself plus the
 * always-present voting menu (bottom-right corner) and turn timer (right slot).
 * Each mode supplies only its own mode-specific controls as children.
 */
function ControlBarShell({
  gameState,
  myPlayerId,
  tableId,
  children,
}: ControlBarProps & { children?: ReactNode }) {
  return (
    <Bar data-testid="control-bar">
      {children}
      <CornerMenu>
        <VotingMenu gameState={gameState} myPlayerId={myPlayerId} tableId={tableId} />
      </CornerMenu>
      <RightSlot>
        <TurnTimer gameState={gameState} myPlayerId={myPlayerId} />
      </RightSlot>
    </Bar>
  )
}

export function ControlBar({ gameState, myPlayerId, tableId }: ControlBarProps) {
  const { mode, isMyTurn, amIReady, isGameOver, amountToCall, minRaise, maxRaiseOnTop, canRaise } =
    selectControls(gameState, myPlayerId)

  const [raiseValue, setRaiseValue] = useState(0)
  const [busy, setBusy] = useState(false)
  const { failed: actionFailed, showError } = useErrorFlash()

  async function withBusy(fn: () => Promise<unknown>, onSuccess?: () => void) {
    setBusy(true)
    try {
      await fn()
      onSuccess?.()
    } catch {
      showError()
    } finally {
      setBusy(false)
    }
  }

  const handleAction = (type: ActionType, value?: number) =>
    withBusy(() => sendAction(tableId, { type, value }), () => setRaiseValue(0))

  const handleReadyUp = () => withBusy(() => readyUp(tableId))
  const handleStartRound = () => withBusy(() => startRound(tableId))
  const handleRestartGame = () => withBusy(() => restartGame(tableId))
  const handleActivate = () => withBusy(() => setPlayerOnline(tableId))

  function handleSliderChange(raw: number) {
    setRaiseValue(snapRaiseValue(raw, minRaise, maxRaiseOnTop))
  }

  const shellProps = { gameState, myPlayerId, tableId }

  if (mode === 'spectating') {
    return <ControlBarShell {...shellProps} />
  }

  if (mode === 'idle') {
    return (
      <ControlBarShell {...shellProps}>
        <Button variant="primary" disabled={busy} onClick={handleActivate}>
          I&#39;m back
        </Button>
        {actionFailed && <ActionError>Could not reactivate</ActionError>}
      </ControlBarShell>
    )
  }

  if (mode === 'lobby') {
    return (
      <ControlBarShell {...shellProps}>
        {!isGameOver && (
          <Button data-testid="btn-ready" variant="primary" disabled={busy || amIReady} onClick={handleReadyUp}>
            {amIReady ? 'Ready ✓' : 'Ready Up'}
          </Button>
        )}
        <Button data-testid="btn-start-round" disabled={busy} onClick={isGameOver ? handleRestartGame : handleStartRound}>
          {isGameOver ? 'Start New Game' : 'Start Round'}
        </Button>
        {actionFailed && <ActionError>Action failed</ActionError>}
      </ControlBarShell>
    )
  }

  const isCalling = raiseValue === 0
  const isAllIn = canRaise && raiseValue > 0 && raiseValue === maxRaiseOnTop
  const callLabel = amountToCall > 0 ? 'Call' : 'Check'
  const fillPercent = maxRaiseOnTop > 0 ? (raiseValue / maxRaiseOnTop) * 100 : 0

  function handleMainAction() {
    if (isCalling) return handleAction('CALL')
    if (isAllIn) return handleAction('ALL_IN')
    return handleAction('RAISE', raiseValue)
  }

  return (
    <ControlBarShell {...shellProps}>
      <Button data-testid="btn-fold" variant="danger" disabled={!isMyTurn || busy} onClick={() => handleAction('FOLD')}>
        Fold
      </Button>
      <Button
        data-testid="btn-call"
        fixedWidth
        variant={isAllIn ? 'primary' : undefined}
        disabled={!isMyTurn || busy}
        onClick={handleMainAction}
      >
        {isCalling ? (
          callLabel
        ) : isAllIn ? (
          'All In'
        ) : (
          <>
            Raise <MonoValue>{raiseValue}</MonoValue>
          </>
        )}
      </Button>
      {actionFailed && <ActionError>Action failed</ActionError>}
      {canRaise && (
        <SliderWrap>
          <RangeInput
            type="range"
            fillPercent={fillPercent}
            min={0}
            max={maxRaiseOnTop}
            step={RAISE_STEP}
            value={raiseValue}
            disabled={!isMyTurn || busy}
            onChange={(e) => handleSliderChange(Number(e.target.value))}
          />
          <span>
            {raiseValue > 0 ? (
              isAllIn ? (
                'All In'
              ) : (
                <>
                  Raise by <MonoValue>{raiseValue}</MonoValue>
                </>
              )
            ) : (
              'Drag to raise'
            )}
          </span>
        </SliderWrap>
      )}
    </ControlBarShell>
  )
}
