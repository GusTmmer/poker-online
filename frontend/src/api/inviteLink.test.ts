import { afterEach, describe, expect, it, vi } from 'vitest'
import { copyToClipboard, inviteLink, parseTableRef } from './inviteLink'

describe('inviteLink', () => {
  it('is the absolute URL of the table page', () => {
    expect(inviteLink(482913, 'https://poker.example.com')).toBe('https://poker.example.com/table/482913')
  })

  it('round-trips through parseTableRef', () => {
    expect(parseTableRef(inviteLink(77, 'http://localhost:5173'))).toBe(77)
  })
})

describe('parseTableRef', () => {
  it.each([
    ['482913', 482913],
    ['  482913 ', 482913],
    ['/table/482913', 482913],
    ['https://poker.example.com/table/482913', 482913],
    ['https://poker.example.com/table/482913?ref=chat', 482913],
    ['poker.example.com/table/482913/', 482913],
  ])('reads %j as table %i', (input, expected) => {
    expect(parseTableRef(input)).toBe(expected)
  })

  it.each(['', 'abc', '0', '/table/', 'https://poker.example.com/', '/table/12x', '12 34'])(
    'rejects %j',
    (input) => {
      expect(parseTableRef(input)).toBeNull()
    },
  )
})

describe('copyToClipboard', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('writes the text and reports success', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    await expect(copyToClipboard('hello')).resolves.toBe(true)
    expect(writeText).toHaveBeenCalledWith('hello')
  })

  it('reports failure when the clipboard is unavailable or refuses', async () => {
    vi.stubGlobal('navigator', {})
    await expect(copyToClipboard('hello')).resolves.toBe(false)
    vi.stubGlobal('navigator', { clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } })
    await expect(copyToClipboard('hello')).resolves.toBe(false)
  })
})
