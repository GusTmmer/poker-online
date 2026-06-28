import styled from '@emotion/styled'
import { AnimatePresence, motion } from 'framer-motion'
import type { Toast } from '../hooks/useToasts'
import { gradient, palette } from '../theme'

const Stack = styled.div`
  position: fixed;
  bottom: 5.5rem;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  z-index: 80;
  align-items: center;
`

const Bubble = styled(motion.div)`
  background: ${gradient.panel};
  border: 1px solid ${palette.gold};
  color: ${palette.cream};
  border-radius: 8px;
  padding: 0.5rem 1rem;
  font-family: 'Cormorant Garamond', Georgia, serif;
  font-size: 0.95rem;
  box-shadow: 0 4px 10px rgba(0, 0, 0, 0.4);
  cursor: pointer;
`

interface ToastsProps {
  toasts: Toast[]
  onDismiss: (id: number) => void
}

export function Toasts({ toasts, onDismiss }: ToastsProps) {
  return (
    <Stack>
      <AnimatePresence>
        {toasts.map((toast) => (
          <Bubble
            key={toast.id}
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -10 }}
            onClick={() => onDismiss(toast.id)}
          >
            {toast.message}
          </Bubble>
        ))}
      </AnimatePresence>
    </Stack>
  )
}
