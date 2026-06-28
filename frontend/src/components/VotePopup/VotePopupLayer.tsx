import { AnimatePresence } from 'framer-motion'
import { useState } from 'react'
import type { GameStateUpdate } from '../../api/types'
import { VotePopup } from './VotePopup'

interface VotePopupLayerProps {
  gameState: GameStateUpdate
  tableId: number
}

export function VotePopupLayer({ gameState, tableId }: VotePopupLayerProps) {
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [votedSessions, setVotedSessions] = useState<Set<string>>(new Set())

  // When the set of live votes changes, drop bookkeeping for resolved ones so
  // these sets can't grow without bound over a long table session. Adjusting
  // state during render (React's documented pattern) instead of in an effect
  // avoids an extra commit + the cascading-render lint.
  const activeIds = gameState.activeVotes.map((v) => v.sessionId).join(',')
  const [prevActiveIds, setPrevActiveIds] = useState(activeIds)
  if (activeIds !== prevActiveIds) {
    setPrevActiveIds(activeIds)
    const live = new Set(gameState.activeVotes.map((v) => v.sessionId))
    setVotedSessions((prev) => {
      const next = new Set([...prev].filter((id) => live.has(id)))
      return next.size === prev.size ? prev : next
    })
    setExpandedId((cur) => (cur && live.has(cur) ? cur : null))
  }

  return (
    <AnimatePresence>
      {gameState.activeVotes.map((vote, index) => (
        <VotePopup
          key={vote.sessionId}
          vote={vote}
          gameState={gameState}
          tableId={tableId}
          cornerIndex={index}
          hasVoted={votedSessions.has(vote.sessionId)}
          onVoted={() => setVotedSessions((s) => new Set(s).add(vote.sessionId))}
          expanded={vote.sessionId === expandedId}
          onToggleExpand={() =>
            setExpandedId((current) => (current === vote.sessionId ? null : vote.sessionId))
          }
        />
      ))}
    </AnimatePresence>
  )
}
