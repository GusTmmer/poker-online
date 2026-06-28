// Single source of truth for the felt/gold casino palette. Every styled
// component imports the string tokens; the Pixi renderer imports the matching
// numeric (0x) tokens so the two layers can never drift apart.
//
// Keep `palette` and `hex` in lockstep — each `hex` entry is the numeric form of
// the same-named `palette` string.

export const palette = {
  gold: '#d8b65a',
  goldBright: '#f5d98a',
  goldMid: '#c4972e',
  goldDark: '#9c7a25',
  bronze: '#8a6a32',

  cream: '#ede1c8',
  creamMuted: '#c9b896',
  parchment: '#a08060',

  panelTop: '#2a1810',
  panelBottom: '#170d09',
  ink: '#1a0e10',
  bgDark: '#0a0a0a',

  greenTop: '#2c7a52',
  greenBottom: '#184a30',
  winGreen: '#3ddc84',

  dangerTop: '#8a2a38',
  dangerBottom: '#5c1620',
  danger: '#c14555',
  errorText: '#ff8a8a',

  pauseBlue: '#1a3a6b',
} as const

// Reusable surface/button gradients (top → bottom). Centralized because the same
// few gradients are repeated across nearly every panel and button.
export const gradient = {
  panel: `linear-gradient(180deg, ${palette.panelTop} 0%, ${palette.panelBottom} 100%)`,
  gold: `linear-gradient(180deg, ${palette.gold} 0%, ${palette.goldDark} 100%)`,
  green: `linear-gradient(180deg, ${palette.greenTop} 0%, ${palette.greenBottom} 100%)`,
  danger: `linear-gradient(180deg, ${palette.dangerTop} 0%, ${palette.dangerBottom} 100%)`,
} as const

// Numeric forms for the Pixi (WebGL) layer, which can't consume CSS strings.
export const hex = {
  gold: 0xd8b65a,
  cream: 0xede1c8,
  creamMuted: 0xc9b896,
  parchment: 0xa08060,
  ink: 0x1a0e10,
  bgDark: 0x0a0a0a,
  winGreen: 0x3ddc84,
} as const
