import { useEffect, useRef } from 'react'
import type { GameStateUpdate } from '../api/types'

export function useStateLogger(gameState: GameStateUpdate | null) {
  const prevRef = useRef<GameStateUpdate | null>(null)

  useEffect(() => {
    // Dev-only diagnostics — never log game state in production builds.
    if (!import.meta.env.DEV) return
    if (!gameState) return
    const prev = prevRef.current
    prevRef.current = gameState

    if (!prev) {
      console.group('[poker] Initial state received')
      console.log('status:', gameState.gameStatus, '| stage:', gameState.roundStage ?? '–')
      console.log('players:', gameState.players.map((p) => `${p.name}($${p.chips})`).join(', '))
      console.groupEnd()
      return
    }

    const changes: string[] = []

    if (prev.gameStatus !== gameState.gameStatus)
      changes.push(`gameStatus: ${prev.gameStatus} → ${gameState.gameStatus}`)

    if (prev.roundStage !== gameState.roundStage)
      changes.push(`stage: ${prev.roundStage ?? '–'} → ${gameState.roundStage ?? '–'}`)

    if (prev.communityCards.length !== gameState.communityCards.length) {
      const added = gameState.communityCards.slice(prev.communityCards.length)
      changes.push(`community: +[${added.join(', ')}] (total ${gameState.communityCards.length})`)
    }

    if (prev.nextPlayerIdToAct !== gameState.nextPlayerIdToAct) {
      const name = (id: number | null) =>
        id == null ? 'none' : (gameState.players.find((p) => p.id === id)?.name ?? `#${id}`)
      changes.push(`nextToAct: ${name(prev.nextPlayerIdToAct)} → ${name(gameState.nextPlayerIdToAct)}`)
    }

    if (prev.potTotal !== gameState.potTotal)
      changes.push(`pot: $${prev.potTotal} → $${gameState.potTotal}`)

    const chipChanges = gameState.players.flatMap((p) => {
      const pp = prev.players.find((x) => x.id === p.id)
      if (!pp || pp.chips === p.chips) return []
      const delta = p.chips - pp.chips
      return [`${p.name}: ${delta > 0 ? '+' : ''}${delta}`]
    })
    if (chipChanges.length) changes.push(`chips: [${chipChanges.join(', ')}]`)

    const statusChanges = gameState.players.flatMap((p) => {
      const pp = prev.players.find((x) => x.id === p.id)
      if (!pp || pp.status === p.status) return []
      return [`${p.name}: ${pp.status} → ${p.status}`]
    })
    if (statusChanges.length) changes.push(`playerStatus: [${statusChanges.join(', ')}]`)

    if (changes.length === 0) return

    console.group(`[poker] State update`)
    changes.forEach((c) => console.log(c))
    console.groupEnd()
  })
}
