import { useParams } from 'react-router-dom'
import styled from '@emotion/styled'
import { SessionProvider } from '../context/SessionContext'
import { useSession } from '../context/useSession'
import { JoinForm } from './JoinForm'
import { GameScreen } from './GameScreen'
import { palette } from '../theme'

const StatusText = styled.p<{ error?: boolean }>`
  text-align: center;
  margin-top: 4rem;
  ${(p) => p.error && `color: ${palette.errorText};`}
`

function TableSessionGate() {
  const { loading, tableInfo, error } = useSession()

  if (loading && !tableInfo) {
    return <StatusText>Loading table…</StatusText>
  }

  if (error && !tableInfo) {
    return <StatusText error>{error}</StatusText>
  }

  if (!tableInfo) {
    return null
  }

  if (!tableInfo.hasSession) {
    return <JoinForm />
  }

  return <GameScreen />
}

export function TablePage() {
  const { tableId } = useParams<{ tableId: string }>()
  const numericTableId = Number(tableId)

  if (!tableId || !Number.isInteger(numericTableId) || numericTableId <= 0) {
    return <StatusText error>Invalid table id.</StatusText>
  }

  return (
    <SessionProvider key={numericTableId} tableId={numericTableId}>
      <TableSessionGate />
    </SessionProvider>
  )
}
