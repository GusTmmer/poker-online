import type { PlayerView, PotView } from '../api/types'

/** "Main pot", "Side pot", "Side pot 2", … — in the order the server lists them. */
export function potName(index: number): string {
  if (index === 0) return 'Main pot'
  return index === 1 ? 'Side pot' : `Side pot ${index}`
}

/**
 * One line per decided pot, shown under the pot after a showdown:
 * "Ada wins — Queen kicker", "Split — Ada & Gus". With side pots each line names its pot.
 * Empty until the server has resolved the winners.
 */
export function potResultLines(pots: PotView[], players: PlayerView[]): string[] {
  const decided = pots.filter((pot) => pot.winnerIds.length > 0)
  if (decided.length === 0) return []

  const nameOf = (id: number) => players.find((p) => p.id === id)?.name ?? `Player ${id}`
  const describe = (pot: PotView) => {
    const names = pot.winnerIds.map(nameOf)
    return names.length > 1
      ? `Split — ${names.slice(0, -1).join(', ')} & ${names[names.length - 1]}`
      : `${names[0]} wins${pot.reason ? ` — ${pot.reason}` : ''}`
  }

  // The same result in every pot (one player scooping them all) reads as a single line.
  const results = decided.map(describe)
  if (decided.length === pots.length && results.every((r) => r === results[0])) return [results[0]]

  return pots.flatMap((pot, index) =>
    pot.winnerIds.length === 0 ? [] : [`${potName(index)}: ${describe(pot)}`],
  )
}

/** Everyone who won a pot at showdown; empty when the frame carries no results. */
export function potWinnerIds(pots: PotView[]): number[] {
  return [...new Set(pots.flatMap((pot) => pot.winnerIds))]
}
