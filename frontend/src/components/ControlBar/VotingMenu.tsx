import { useEffect, useRef, useState } from 'react'
import { useErrorFlash } from '../../hooks/useErrorFlash'
import styled from '@emotion/styled'
import type { GameStateUpdate } from '../../api/types'
import { createVoteSession, renameTable, requestKick, requestPause, requestUnpause } from '../../api/client'
import { useSession } from '../../context/SessionContext'
import { gradient, palette } from '../../theme'

const MenuRoot = styled.div`
  position: relative;
`

const MenuButton = styled.button`
  width: 2.4rem;
  height: 2.4rem;
  border-radius: 50%;
  border: 1px solid ${palette.gold};
  background: ${gradient.panel};
  color: ${palette.gold};
  font-size: 1.1rem;
  cursor: pointer;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.15);
  transition: filter 0.15s, box-shadow 0.15s;

  &:hover {
    filter: brightness(1.25);
    box-shadow:
      inset 0 1px 0 rgba(255, 255, 255, 0.15),
      0 0 0.5rem rgba(216, 182, 90, 0.25);
  }

  &:focus-visible {
    outline: 2px solid ${palette.goldBright};
    outline-offset: 2px;
  }
`

const ErrorBadge = styled.span`
  position: absolute;
  top: -0.3rem;
  right: -0.3rem;
  background: ${palette.dangerTop};
  color: ${palette.cream};
  font-size: 0.65rem;
  border-radius: 4px;
  padding: 0.1rem 0.3rem;
  pointer-events: none;
`

const Dropdown = styled.div`
  position: absolute;
  bottom: 3rem;
  right: 0;
  background: ${gradient.panel};
  border: 1px solid ${palette.bronze};
  border-radius: 8px;
  padding: 0.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
  min-width: 180px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.5);
`

const Item = styled.button`
  background: none;
  border: none;
  color: ${palette.cream};
  font-family: 'Cormorant Garamond', Georgia, serif;
  text-align: left;
  padding: 0.4rem 0.5rem;
  border-radius: 4px;
  cursor: pointer;
  font-size: 0.95rem;

  &:hover {
    background: rgba(216, 182, 90, 0.18);
  }
`

const Empty = styled.div`
  padding: 0.4rem 0.5rem;
  opacity: 0.6;
  font-size: 0.85rem;
`

const Divider = styled.div`
  border-top: 1px solid ${palette.bronze};
  margin: 0.2rem 0;
`

const RenameInput = styled.input`
  padding: 0.4rem 0.5rem;
  margin-bottom: 0.2rem;
  font-family: 'Cormorant Garamond', Georgia, serif;
  font-size: 0.95rem;
  color: ${palette.cream};
  background: ${palette.panelBottom};
  border: 1px solid ${palette.gold};
  border-radius: 4px;

  &:focus-visible {
    outline: 2px solid ${palette.goldBright};
    outline-offset: 1px;
  }
`

type MenuView = null | 'main' | 'kick' | 'rename'

interface VotingMenuProps {
  gameState: GameStateUpdate
  myPlayerId: number
  tableId: number
}

export function VotingMenu({ gameState, myPlayerId, tableId }: VotingMenuProps) {
  const [menuView, setMenuView] = useState<MenuView>(null)
  const [nameDraft, setNameDraft] = useState('')
  const [savingName, setSavingName] = useState(false)
  const { tableInfo, refresh } = useSession()
  const { failed, showError } = useErrorFlash()
  const rootRef = useRef<HTMLDivElement>(null)

  function openRename() {
    setNameDraft(tableInfo?.name ?? '')
    setMenuView('rename')
  }

  async function saveName() {
    setSavingName(true)
    try {
      await renameTable(tableId, nameDraft)
      await refresh()
      setMenuView(null)
    } catch {
      showError()
    } finally {
      setSavingName(false)
    }
  }

  useEffect(() => {
    if (!menuView) return
    function handleClickOutside(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setMenuView(null)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [menuView])

  async function fire(action: () => Promise<unknown>) {
    setMenuView(null)
    try {
      await action()
    } catch {
      showError()
    }
  }

  const others = gameState.players.filter((p) => p.id !== myPlayerId && p.status !== 'ELIMINATED')

  return (
    <MenuRoot ref={rootRef}>
      <MenuButton onClick={() => setMenuView((v) => (v ? null : 'main'))} aria-label="Voting menu">
        ⋮
      </MenuButton>
      {failed && <ErrorBadge>failed</ErrorBadge>}
      {menuView === 'main' && (
        <Dropdown>
          <Item onClick={openRename}>Rename table…</Item>
          <Divider />
          {gameState.gameStatus === 'PAUSED' ? (
            <Item onClick={() => fire(() => requestUnpause(tableId))}>Vote: Unpause game</Item>
          ) : (
            <Item onClick={() => fire(() => requestPause(tableId))}>Vote: Pause game</Item>
          )}
          <Item onClick={() => fire(() => createVoteSession(tableId, { resolution: 'RESTART_GAME' }))}>
            Vote: Restart game
          </Item>
          <Item onClick={() => fire(() => createVoteSession(tableId, { resolution: 'INCREASE_BLINDS' }))}>
            Vote: Increase blinds
          </Item>
          <Divider />
          <Item onClick={() => setMenuView('kick')}>Vote: Kick player…</Item>
        </Dropdown>
      )}
      {menuView === 'rename' && (
        <Dropdown>
          <RenameInput
            autoFocus
            value={nameDraft}
            maxLength={40}
            placeholder="Table name"
            disabled={savingName}
            onChange={(e) => setNameDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') void saveName()
              if (e.key === 'Escape') setMenuView('main')
            }}
          />
          <Item onClick={() => void saveName()}>Save name</Item>
          <Divider />
          <Item onClick={() => setMenuView('main')}>← Back</Item>
        </Dropdown>
      )}
      {menuView === 'kick' && (
        <Dropdown>
          {others.length === 0 && <Empty>No other players</Empty>}
          {others.map((p) => (
            <Item key={p.id} onClick={() => fire(() => requestKick(tableId, { targetPlayerId: p.id }))}>
              Kick {p.name}
            </Item>
          ))}
          <Divider />
          <Item onClick={() => setMenuView('main')}>← Back</Item>
        </Dropdown>
      )}
    </MenuRoot>
  )
}
