import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import styled from '@emotion/styled'
import { createTable, joinTable } from '../api/client'
import { ApiError } from '../api/types'
import { palette } from '../theme'
import {
  FormPage,
  FormCard,
  FormTitle,
  FormInput,
  FormLabel,
  FormSubmitButton,
  FormErrorText,
} from '../components/ui/FormCard'

const TabBar = styled.div`
  display: flex;
  border: 1px solid ${palette.bronze};
  border-radius: 6px;
  overflow: hidden;
`

const Tab = styled.button<{ active: boolean }>`
  flex: 1;
  padding: 0.5rem;
  border: none;
  background: ${({ active }) => (active ? 'rgba(216, 182, 90, 0.15)' : 'transparent')};
  color: ${({ active }) => (active ? palette.gold : '#7a6a4a')};
  font-family: 'Cinzel', Georgia, serif;
  font-size: 0.9rem;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  cursor: pointer;
  transition: color 0.15s, background 0.15s;

  &:first-of-type {
    border-right: 1px solid ${palette.bronze};
  }
`

// Labels row + inputs row share the same 2-column grid so inputs always align,
// regardless of how many lines the label text wraps to.
const FieldRow = styled.div`
  display: grid;
  grid-template-columns: 1fr 1fr;
  grid-template-rows: auto auto;
  column-gap: 0.75rem;
  row-gap: 0.35rem;
`

const FieldLabel = styled.span`
  font-size: 0.95rem;
  color: ${palette.creamMuted};
  align-self: end;
`

type Mode = 'create' | 'join'

// Number inputs emit '' (→ NaN) when cleared; keep the previous value in that
// case so we never push a NaN into the create-table payload.
const numericSetter =
  (set: (n: number) => void) => (e: React.ChangeEvent<HTMLInputElement>) => {
    const n = Number(e.target.value)
    if (!Number.isNaN(n)) set(n)
  }

export function HomePage() {
  const navigate = useNavigate()
  const [mode, setMode] = useState<Mode>('create')

  const [createName, setCreateName] = useState('')
  const [maxPlayers, setMaxPlayers] = useState(6)
  const [startingChips, setStartingChips] = useState(1000)
  const [turnTimerSeconds, setTurnTimerSeconds] = useState(30)
  const [blindEscalationOrbits, setBlindEscalationOrbits] = useState(2)
  const [blindEscalationMultiplier, setBlindEscalationMultiplier] = useState(2.0)

  const [joinName, setJoinName] = useState('')
  const [tableIdStr, setTableIdStr] = useState('')

  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function switchMode(next: Mode) {
    setMode(next)
    setError(null)
  }

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault()
    if (!createName.trim()) return
    setSubmitting(true)
    setError(null)
    try {
      const response = await createTable({
        playerName: createName.trim(),
        maxPlayers,
        startingChips,
        turnTimerSeconds,
        blindEscalationOrbits,
        blindEscalationMultiplier,
      })
      navigate(`/table/${response.tableId}`)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Failed to create table')
    } finally {
      setSubmitting(false)
    }
  }

  async function handleJoin(e: React.FormEvent) {
    e.preventDefault()
    const tableId = Number(tableIdStr)
    if (!joinName.trim() || !tableId) return
    setSubmitting(true)
    setError(null)
    try {
      await joinTable(tableId, { playerName: joinName.trim() })
      navigate(`/table/${tableId}`)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Failed to join table')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <FormPage>
      <FormCard onSubmit={mode === 'create' ? handleCreate : handleJoin}>
        <FormTitle>{mode === 'create' ? 'Start a poker table' : 'Join a table'}</FormTitle>
        <TabBar>
          <Tab type="button" active={mode === 'create'} onClick={() => switchMode('create')}>
            Create
          </Tab>
          <Tab type="button" active={mode === 'join'} onClick={() => switchMode('join')}>
            Join
          </Tab>
        </TabBar>

        {mode === 'create' ? (
          <>
            <FormLabel>
              Your name
              <FormInput
                value={createName}
                onChange={(e) => setCreateName(e.target.value)}
                placeholder="Alice"
                maxLength={24}
                required
              />
            </FormLabel>
            <FieldRow>
              <FieldLabel>Max players</FieldLabel>
              <FieldLabel>Starting chips</FieldLabel>
              <FormInput
                type="number"
                min={2}
                max={10}
                value={maxPlayers}
                onChange={numericSetter(setMaxPlayers)}
              />
              <FormInput
                type="number"
                min={100}
                step={100}
                value={startingChips}
                onChange={numericSetter(setStartingChips)}
              />
            </FieldRow>
            <FieldRow>
              <FieldLabel>Turn timer (s)</FieldLabel>
              <FieldLabel>Blind increase every</FieldLabel>
              <FormInput
                type="number"
                min={10}
                max={120}
                value={turnTimerSeconds}
                onChange={numericSetter(setTurnTimerSeconds)}
              />
              <FormInput
                type="number"
                min={1}
                max={10}
                value={blindEscalationOrbits}
                onChange={numericSetter(setBlindEscalationOrbits)}
                placeholder="orbits"
              />
            </FieldRow>
            <FormLabel>
              Blind multiplier
              <FormInput
                type="number"
                min={1.1}
                max={5}
                step={0.1}
                value={blindEscalationMultiplier}
                onChange={numericSetter(setBlindEscalationMultiplier)}
              />
            </FormLabel>
          </>
        ) : (
          <>
            <FormLabel>
              Table ID
              <FormInput
                inputMode="numeric"
                value={tableIdStr}
                onChange={(e) => setTableIdStr(e.target.value.replace(/\D/g, ''))}
                placeholder="123456"
                required
              />
            </FormLabel>
            <FormLabel>
              Your name
              <FormInput
                value={joinName}
                onChange={(e) => setJoinName(e.target.value)}
                placeholder="Alice"
                maxLength={24}
                required
              />
            </FormLabel>
          </>
        )}

        {error && <FormErrorText>{error}</FormErrorText>}
        <FormSubmitButton type="submit" disabled={submitting}>
          {mode === 'create'
            ? submitting
              ? 'Creating…'
              : 'Create table'
            : submitting
              ? 'Joining…'
              : 'Join table'}
        </FormSubmitButton>
      </FormCard>
    </FormPage>
  )
}
