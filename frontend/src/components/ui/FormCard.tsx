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
  /* Slightly tighter horizontal padding (width fixed via border-box) so the two-column
     field grid is wide enough to keep labels like "Blind increase every" on one line. */
  padding: 2.5rem 1.75rem;
  width: 380px;
  max-width: calc(100vw - 2rem);
  background: ${gradient.panel};
  border: 1px solid ${palette.bronze};
  border-radius: 12px;
  /* Inner gold hairline inset from the border — a double frame */
  box-shadow:
    inset 0 0 0 4px rgba(23, 13, 9, 1),
    inset 0 0 0 5px rgba(216, 182, 90, 0.35),
    0 10px 30px rgba(0, 0, 0, 0.55);
`

export const FormTitle = styled.h1`
  margin: 0;
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
  box-shadow: inset 0 2px 4px rgba(0, 0, 0, 0.4);
  transition: border-color 0.15s, box-shadow 0.15s;

  &::placeholder {
    color: ${palette.parchment};
    opacity: 0.6;
  }

  &:focus {
    outline: none;
    border-color: ${palette.gold};
    box-shadow:
      inset 0 2px 4px rgba(0, 0, 0, 0.4),
      0 0 0 3px rgba(216, 182, 90, 0.18);
  }
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
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.3),
    inset 0 -1px 0 rgba(0, 0, 0, 0.25),
    0 0.2rem 0.5rem rgba(0, 0, 0, 0.45);
  transition: filter 0.15s, transform 0.15s, box-shadow 0.15s;

  &:hover:not(:disabled) {
    filter: brightness(1.1);
    transform: translateY(-1px);
  }

  &:active:not(:disabled) {
    filter: brightness(0.95);
    transform: translateY(0);
  }

  &:focus-visible {
    outline: 2px solid ${palette.goldBright};
    outline-offset: 2px;
  }

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
