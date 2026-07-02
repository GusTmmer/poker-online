import styled from '@emotion/styled'

// A spade between two fading hairlines — the house ornament under form titles.
const OrnamentRow = styled.div`
  display: flex;
  align-items: center;
  gap: 0.6rem;
  margin: -0.4rem 0 0.1rem;
  color: rgba(216, 182, 90, 0.6);
  font-size: 0.8rem;
`

const RuleLeft = styled.span`
  flex: 1;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(216, 182, 90, 0.45));
`

const RuleRight = styled.span`
  flex: 1;
  height: 1px;
  background: linear-gradient(90deg, rgba(216, 182, 90, 0.45), transparent);
`

export function FormOrnament() {
  return (
    <OrnamentRow aria-hidden="true">
      <RuleLeft />
      ♠
      <RuleRight />
    </OrnamentRow>
  )
}
