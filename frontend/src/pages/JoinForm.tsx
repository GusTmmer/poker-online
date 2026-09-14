import { useState } from 'react'
import styled from '@emotion/styled'
import { useSession } from '../context/SessionContext'
import {
  FormPage,
  FormCard,
  FormTitle,
  FormInput,
  FormSubmitButton,
  FormErrorText,
} from '../components/ui/FormCard'
import { FormOrnament } from '../components/ui/FormOrnament'
import { tableLabel } from '../api/tableLabel'
import { palette } from '../theme'

const Invitation = styled.p`
  margin: 0;
  text-align: center;
  color: ${palette.creamMuted};
  font-size: 1rem;
  line-height: 1.4;
`

const TableName = styled.span`
  display: block;
  color: ${palette.cream};
  font-family: 'Cinzel', Georgia, serif;
  font-size: 1.1rem;
`

export function JoinForm() {
  const { join, error, tableInfo } = useSession()
  const [playerName, setPlayerName] = useState('')
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!playerName.trim()) return
    setSubmitting(true)
    try {
      await join(playerName.trim())
    } finally {
      setSubmitting(false)
    }
  }

  const full = tableInfo != null && tableInfo.players.length >= tableInfo.maxPlayers
  const closed = tableInfo != null && !tableInfo.isOpen

  return (
    <FormPage>
      <FormCard onSubmit={handleSubmit}>
        <FormTitle>Join the table</FormTitle>
        <FormOrnament />
        {tableInfo && (
          <Invitation data-testid="join-invitation">
            You're invited to
            <TableName>{tableLabel(tableInfo.name, tableInfo.tableId)}</TableName>
            {seatedSummary(tableInfo.players.map((p) => p.name), tableInfo.maxPlayers)}
          </Invitation>
        )}
        <FormInput
          value={playerName}
          onChange={(e) => setPlayerName(e.target.value)}
          placeholder="Your name"
          maxLength={24}
          required
        />
        {error && <FormErrorText>{error}</FormErrorText>}
        <FormSubmitButton type="submit" disabled={submitting || full || closed}>
          {full ? 'Table is full' : closed ? 'Table is closed' : submitting ? 'Joining…' : 'Join'}
        </FormSubmitButton>
      </FormCard>
    </FormPage>
  )
}

/** "Alice, Bob and 2 others · 4 of 6 seats taken". */
function seatedSummary(names: string[], maxPlayers: number): string {
  const seats = `${names.length} of ${maxPlayers} seats taken`
  if (names.length === 0) return seats
  const shown = names.slice(0, 2)
  const rest = names.length - shown.length
  const who = rest > 0 ? `${shown.join(', ')} and ${rest} other${rest === 1 ? '' : 's'}` : shown.join(' and ')
  return `${who} · ${seats}`
}
