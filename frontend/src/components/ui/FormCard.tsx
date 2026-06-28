import styled from '@emotion/styled'
import { gradient, palette } from '../../theme'

export const FormPage = styled.div`
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100%;
`

export const FormCard = styled.form`
  display: flex;
  flex-direction: column;
  gap: 1.1rem;
  padding: 2.5rem;
  width: 380px;
  background: ${gradient.panel};
  border: 1px solid ${palette.bronze};
  border-radius: 12px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.5);
`

export const FormTitle = styled.h1`
  margin: 0 0 0.5rem;
  font-size: 1.65rem;
  line-height: 1.25;
  color: ${palette.gold};
  text-align: center;
  text-wrap: balance;
`

export const FormInput = styled.input`
  width: 100%;
  box-sizing: border-box;
  padding: 0.6rem 0.75rem;
  border-radius: 6px;
  border: 1px solid ${palette.bronze};
  background: ${palette.panelBottom};
  color: ${palette.cream};
  font-family: 'Cormorant Garamond', Georgia, serif;
  font-size: 1.15rem;
`

export const FormLabel = styled.label`
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  font-size: 0.95rem;
  color: ${palette.creamMuted};
`

export const FormSubmitButton = styled.button`
  margin-top: 0.5rem;
  padding: 0.7rem;
  border-radius: 6px;
  border: 1px solid ${palette.gold};
  font-family: 'Cinzel', Georgia, serif;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  background: ${gradient.gold};
  color: ${palette.ink};
  font-size: 1.05rem;
  font-weight: 600;
  cursor: pointer;

  &:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }
`

export const FormErrorText = styled.p`
  margin: 0;
  color: ${palette.errorText};
  font-size: 0.95rem;
`
