import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import styled from '@emotion/styled'
import { keyframes } from '@emotion/react'
import { useMyTables } from '../api/useMyTables'
import { leaveTable } from '../api/client'
import { tableLabel } from '../api/tableLabel'
import { gradient, palette } from '../theme'

// Floating, self-contained panel pinned to the top-right. It only appears when this
// browser holds table sessions, so the main menu's centre stays uncluttered.
const Panel = styled.div`
  position: fixed;
  top: 1rem;
  right: 1rem;
  z-index: 220;
  width: 392px;
  max-width: calc(100vw - 2rem);
  display: flex;
  flex-direction: column;
  background: ${gradient.panel};
  border: 1px solid ${palette.bronze};
  border-radius: 10px;
  box-shadow:
    inset 0 0 0 3px rgba(23, 13, 9, 0.9),
    inset 0 0 0 4px rgba(216, 182, 90, 0.3),
    0 10px 28px rgba(0, 0, 0, 0.55);
`

const Header = styled.button`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.7rem 0.9rem;
  background: transparent;
  border: none;
  cursor: pointer;
  font-family: 'Cinzel', Georgia, serif;
  font-size: 0.8rem;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: ${palette.gold};

  &:focus-visible {
    outline: 2px solid ${palette.goldBright};
    outline-offset: -2px;
  }
`

const Chevron = styled.span<{ open: boolean }>`
  font-size: 0.7rem;
  color: ${palette.creamMuted};
  transition: transform 0.15s;
  transform: rotate(${(p) => (p.open ? '0deg' : '-90deg')});
`

const List = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  padding: 0 0.7rem 0.7rem;
`

const Row = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.6rem;
  padding: 0.5rem 0.65rem;
  border: 1px solid rgba(138, 106, 50, 0.5);
  border-radius: 6px;
  background: rgba(216, 182, 90, 0.05);
`

const Meta = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  min-width: 0;
`

const Name = styled.span`
  color: ${palette.cream};
  font-size: 0.92rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
`

const Sub = styled.span`
  color: #7a6a4a;
  font-size: 0.74rem;
  letter-spacing: 0.02em;
`

const Actions = styled.div`
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 0.4rem;
`

const RejoinButton = styled.button`
  padding: 0.35rem 0.8rem;
  border-radius: 6px;
  border: 1px solid ${palette.gold};
  background: transparent;
  color: ${palette.gold};
  font-family: 'Cinzel', Georgia, serif;
  font-size: 0.75rem;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  cursor: pointer;
  transition: background 0.15s;

  &:hover {
    background: rgba(216, 182, 90, 0.15);
  }
`

const TrashButton = styled.button`
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0.35rem 0.5rem;
  border-radius: 6px;
  border: 1px solid rgba(138, 106, 50, 0.5);
  background: transparent;
  color: ${palette.parchment};
  font-size: 0.9rem;
  line-height: 1;
  cursor: pointer;
  transition: color 0.15s, border-color 0.15s, background 0.15s;

  &:hover:not(:disabled) {
    color: ${palette.dangerBottom};
    border-color: ${palette.dangerBottom};
    background: rgba(140, 30, 30, 0.12);
  }
  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
`

// The confirm pair grows out of the trash button's spot: it slides in from the right and
// expands, so the destructive "Confirm" arrives deliberately rather than snapping in.
const revealConfirm = keyframes`
  from { opacity: 0; transform: translateX(8px); }
  to   { opacity: 1; transform: translateX(0); }
`

const ConfirmActions = styled.div`
  display: flex;
  align-items: stretch;
  gap: 0.4rem;
  animation: ${revealConfirm} 0.16s ease-out;
`

// Cancel is the safe default, so it's deliberately the big, easy target; Confirm is small.
const CancelButton = styled.button`
  flex: 1 1 auto;
  min-width: 96px;
  padding: 0.4rem 0.9rem;
  border-radius: 6px;
  border: 1px solid ${palette.gold};
  background: rgba(216, 182, 90, 0.12);
  color: ${palette.gold};
  font-family: 'Cinzel', Georgia, serif;
  font-size: 0.78rem;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  cursor: pointer;
  transition: background 0.15s;

  &:hover {
    background: rgba(216, 182, 90, 0.22);
  }
`

const ConfirmButton = styled.button`
  flex: 0 0 auto;
  padding: 0.35rem 0.5rem;
  border-radius: 6px;
  border: 1px solid ${palette.dangerBottom};
  background: rgba(140, 30, 30, 0.18);
  color: ${palette.cream};
  font-size: 0.72rem;
  letter-spacing: 0.02em;
  text-transform: uppercase;
  cursor: pointer;
  transition: background 0.15s;

  &:hover:not(:disabled) {
    background: rgba(140, 30, 30, 0.34);
  }
  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
`

const STATUS_LABEL: Record<string, string> = {
  WAITING: 'Waiting',
  RUNNING: 'In progress',
  PAUSED: 'Paused',
}

export function MyTablesPanel() {
  const navigate = useNavigate()
  const { tables } = useMyTables()
  const [open, setOpen] = useState(true)
  const [confirmingId, setConfirmingId] = useState<number | null>(null)
  const [leaving, setLeaving] = useState<number | null>(null)

  async function handleLeave(tableId: number) {
    if (leaving !== null) return
    setLeaving(tableId)
    try {
      await leaveTable(tableId)
      // Row disappears via the 'left' event; nothing else to reset.
    } catch {
      // Failed — fall back to the normal row so the user can retry.
      setConfirmingId(null)
    } finally {
      setLeaving(null)
    }
  }

  if (tables.length === 0) return null

  return (
    <Panel>
      <Header type="button" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
        <span>Your tables ({tables.length})</span>
        <Chevron open={open} aria-hidden>
          ▼
        </Chevron>
      </Header>
      {open && (
        <List>
          {tables.map((t) => {
            const label = tableLabel(t.name, t.tableId)
            return (
              <Row key={t.tableId}>
                <Meta>
                  <Name>{label}</Name>
                  <Sub>
                    {t.playerName} · {STATUS_LABEL[t.gameStatus] ?? t.gameStatus} · {t.playerCount}/
                    {t.maxPlayers}
                  </Sub>
                </Meta>
                {confirmingId === t.tableId ? (
                  <ConfirmActions>
                    <CancelButton type="button" onClick={() => setConfirmingId(null)}>
                      Cancel
                    </CancelButton>
                    <ConfirmButton
                      type="button"
                      title={`Leave and remove ${label} — cannot be undone`}
                      aria-label={`Confirm leaving ${label}`}
                      disabled={leaving === t.tableId}
                      onClick={() => void handleLeave(t.tableId)}
                    >
                      Leave
                    </ConfirmButton>
                  </ConfirmActions>
                ) : (
                  <Actions>
                    <RejoinButton type="button" onClick={() => navigate(`/table/${t.tableId}`)}>
                      Rejoin
                    </RejoinButton>
                    <TrashButton
                      type="button"
                      title="Leave and remove this table"
                      aria-label={`Leave and remove ${label}`}
                      onClick={() => setConfirmingId(t.tableId)}
                    >
                      🗑
                    </TrashButton>
                  </Actions>
                )}
              </Row>
            )
          })}
        </List>
      )}
    </Panel>
  )
}
