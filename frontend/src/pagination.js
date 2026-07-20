export function getPageCount(total, pageSize) {
  const numericTotal = Number(total)
  const normalizedTotal = Number.isFinite(numericTotal) ? Math.max(0, numericTotal) : 0
  const normalizedPageSize = Number(pageSize)
  if (!Number.isFinite(normalizedPageSize) || normalizedPageSize <= 0) return 1
  return Math.max(1, Math.ceil(normalizedTotal / normalizedPageSize))
}

export function clampPage(page, pageCount) {
  const normalizedCount = Math.max(1, Math.trunc(Number(pageCount) || 1))
  const normalizedPage = Math.trunc(Number(page) || 1)
  return Math.min(normalizedCount, Math.max(1, normalizedPage))
}

export function getVisiblePages(page, pageCount, maxVisible = 5) {
  const normalizedCount = Math.max(1, Math.trunc(Number(pageCount) || 1))
  const visibleCount = Math.min(normalizedCount, Math.max(1, Math.trunc(Number(maxVisible) || 1)))
  const currentPage = clampPage(page, normalizedCount)
  const start = Math.max(1, Math.min(currentPage - Math.floor(visibleCount / 2), normalizedCount - visibleCount + 1))

  return Array.from({ length: visibleCount }, (_, index) => start + index)
}

export async function collectAllPages(fetchPage, pageSize = 100) {
  const normalizedPageSize = Math.min(100, Math.max(1, Math.trunc(Number(pageSize) || 100)))
  const collected = []
  let page = 1

  while (true) {
    const result = await fetchPage(page, normalizedPageSize)
    const items = Array.isArray(result?.items) ? result.items : []
    collected.push(...items)
    const total = Number(result?.total)
    if (!Number.isFinite(total) || collected.length >= Math.max(0, total) || items.length === 0) return collected
    page += 1
  }
}
