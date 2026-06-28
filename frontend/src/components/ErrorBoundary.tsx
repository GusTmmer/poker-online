import { Component, type ErrorInfo, type ReactNode } from 'react'
import styled from '@emotion/styled'
import { gradient, palette } from '../theme'

const Fallback = styled.div`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
  max-width: 420px;
  margin: 6rem auto 0;
  padding: 2rem;
  text-align: center;
  background: ${gradient.panel};
  border: 1px solid ${palette.bronze};
  border-radius: 12px;
`

const ReloadButton = styled.button`
  padding: 0.6rem 1.4rem;
  border-radius: 6px;
  border: 1px solid ${palette.gold};
  background: ${gradient.gold};
  color: ${palette.ink};
  font-family: 'Cinzel', Georgia, serif;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  cursor: pointer;
`

interface Props {
  children: ReactNode
}

interface State {
  hasError: boolean
}

/**
 * Catches render-time exceptions anywhere below it (including the Pixi canvas)
 * and shows a recoverable fallback instead of a blank white screen.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false }

  static getDerivedStateFromError(): State {
    return { hasError: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[ErrorBoundary] Unhandled render error:', error, info.componentStack)
  }

  render() {
    if (this.state.hasError) {
      return (
        <Fallback>
          <h2>Something went wrong</h2>
          <p>The table hit an unexpected error. Reloading usually fixes it.</p>
          <ReloadButton onClick={() => window.location.reload()}>Reload</ReloadButton>
        </Fallback>
      )
    }
    return this.props.children
  }
}
