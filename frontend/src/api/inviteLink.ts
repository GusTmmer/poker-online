/**
 * Invite links. A table's page (`/table/:id`) already doubles as its join page — a visitor without a
 * session there gets the join form — so an invite is just that page's absolute URL.
 */
export function inviteLink(tableId: number, origin: string = window.location.origin): string {
  return `${origin}/table/${tableId}`
}

/**
 * Reads a table id out of what someone pasted: a bare id (`123456`), a path (`/table/123456`) or a
 * full invite link. Null when there's no usable id.
 */
export function parseTableRef(input: string): number | null {
  const trimmed = input.trim()
  const match = /^(\d+)$/.exec(trimmed) ?? /\/table\/(\d+)(?:[/?#]|$)/.exec(trimmed)
  if (!match) return null
  const id = Number(match[1])
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

/**
 * Copies [text] to the clipboard. Resolves false when the browser refuses (no clipboard API outside a
 * secure context, or permission denied), so the caller can show the link for manual copying instead.
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    if (!navigator.clipboard) return false
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}
