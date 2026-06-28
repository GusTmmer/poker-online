import styled from '@emotion/styled'
import { AnimatePresence, motion } from 'framer-motion'
import { useState, type MouseEvent } from 'react'
import type { GameStateUpdate, VoteSummary } from '../../api/types'
import { castVote } from '../../api/client'
import { useErrorFlash } from '../../hooks/useErrorFlash'
import { gradient, palette } from '../../theme'

function describeVote(vote: VoteSummary, gameState: GameStateUpdate): string {
  switch (vote.resolutionType) {
    case 'PAUSE_GAME':
      return 'Pause the game?'
    case 'UNPAUSE_GAME':
      return 'Unpause the game?'
    case 'RESTART_GAME':
      return 'Restart the game?'
    case 'INCREASE_BLINDS':
      return 'Increase the blinds?'
    case 'KICK_PLAYER': {
      const target = gameState.players.find((p) => p.id === vote.targetPlayerId)
      return `Kick ${target?.name ?? 'this player'} from the table?`
    }
    default:
      return 'Vote on this proposal'
  }
}

// The corner card and the enlarged card are two *separate* elements sharing a layoutId rather
// than one element whose position is toggled via CSS - Framer's `layout` projection takes over
// the `transform` style to drive the FLIP animation, which clobbers a CSS `translate(-50%,-50%)`
// centering trick. Centering the enlarged card via flexbox instead leaves `transform` free for
// Framer to manage, and the matching `layoutId` is what makes the corner<->center morph continuous.
const Backdrop = styled(motion.div)`
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  z-index: 90;
  display: flex;
  align-items: center;
  justify-content: center;
`

const CornerCard = styled(motion.div)<{ cornerIndex: number }>`
  position: fixed;
  top: ${(p) => 1 + p.cornerIndex * 7}rem;
  right: 1rem;
  width: 270px;
  background: ${gradient.panel};
  border: 1px solid ${palette.gold};
  border-radius: 12px;
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.5);
  cursor: pointer;
  z-index: 100;
  padding: 1rem 1.2rem;
`

const EnlargedCard = styled(motion.div)`
  width: min(420px, 88vw);
  background: ${gradient.panel};
  border: 2px solid ${palette.gold};
  border-radius: 12px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.6);
  cursor: pointer;
  z-index: 100;
  padding: 1.5rem 1.8rem;
`

const Topic = styled.div`
  font-family: 'Cinzel', Georgia, serif;
  font-size: 1.1rem;
  font-weight: 600;
  color: ${palette.cream};
  margin-bottom: 0.5rem;
`

const Counts = styled.div`
  font-size: 0.9rem;
  color: ${palette.creamMuted};
  margin-bottom: 0.6rem;
`

const Buttons = styled.div`
  display: flex;
  gap: 0.6rem;
`

const VoteButton = styled.button<{ kind: 'yes' | 'no' }>`
  flex: 1;
  padding: 0.5rem;
  border-radius: 6px;
  border: 1px solid #d8b65a;
  font-family: 'Cinzel', Georgia, serif;
  font-weight: 600;
  letter-spacing: 0.03em;
  text-transform: uppercase;
  font-size: 0.9rem;
  cursor: pointer;
  background: ${(p) => (p.kind === 'yes' ? gradient.green : gradient.danger)};
  color: ${palette.cream};

  &:disabled {
    opacity: 0.4;
    cursor: not-allowed;
  }
`

interface VotePopupProps {
  vote: VoteSummary
  gameState: GameStateUpdate
  tableId: number
  cornerIndex: number
  hasVoted: boolean
  onVoted: () => void
  expanded: boolean
  onToggleExpand: () => void
}

export function VotePopup({
  vote,
  gameState,
  tableId,
  cornerIndex,
  hasVoted,
  onVoted,
  expanded,
  onToggleExpand,
}: VotePopupProps) {
  const [busy, setBusy] = useState(false)
  const { failed: castFailed, showError } = useErrorFlash()
  const topic = describeVote(vote, gameState)

  async function cast(value: 'yes' | 'no', e: MouseEvent) {
    e.stopPropagation()
    setBusy(true)
    try {
      await castVote(tableId, vote.sessionId, { vote: value })
      onVoted()
    } catch {
      showError()
    } finally {
      setBusy(false)
    }
  }

  const body = (
    <>
      <Topic>{topic}</Topic>
      <Counts>
        {vote.yesCount} yes / {vote.noCount} no — {vote.requiredVotes} needed
      </Counts>
      {castFailed && <Counts style={{ color: palette.danger }}>Vote failed — please try again</Counts>}
      <Buttons>
        <VoteButton kind="yes" disabled={hasVoted || busy} onClick={(e) => cast('yes', e)}>
          Yes
        </VoteButton>
        <VoteButton kind="no" disabled={hasVoted || busy} onClick={(e) => cast('no', e)}>
          No
        </VoteButton>
      </Buttons>
    </>
  )

  return (
    <AnimatePresence>
      {expanded ? (
        <Backdrop
          key="expanded"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          onClick={onToggleExpand}
        >
          <EnlargedCard layoutId={`vote-${vote.sessionId}`} onClick={(e) => e.stopPropagation()}>
            {body}
          </EnlargedCard>
        </Backdrop>
      ) : (
        <CornerCard
          key="corner"
          layoutId={`vote-${vote.sessionId}`}
          cornerIndex={cornerIndex}
          onClick={onToggleExpand}
        >
          {body}
        </CornerCard>
      )}
    </AnimatePresence>
  )
}
