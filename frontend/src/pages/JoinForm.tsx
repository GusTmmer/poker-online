import { useState } from 'react'
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

  return (
    <FormPage>
      <FormCard onSubmit={handleSubmit}>
        <FormTitle>Join the table</FormTitle>
        <FormOrnament />
        <FormInput
          value={playerName}
          onChange={(e) => setPlayerName(e.target.value)}
          placeholder="Your name"
          maxLength={24}
          required
        />
        {error && <FormErrorText>{error}</FormErrorText>}
        <FormSubmitButton type="submit" disabled={submitting || full}>
          {full ? 'Table is full' : submitting ? 'Joining…' : 'Join'}
        </FormSubmitButton>
      </FormCard>
    </FormPage>
  )
}
