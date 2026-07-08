/** Display label for a table: its name if set, otherwise a stable fallback on the id. */
export function tableLabel(name: string, tableId: number): string {
  const trimmed = name.trim()
  return trimmed.length > 0 ? trimmed : `Table ${tableId}`
}
