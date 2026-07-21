function timestampValue(value) {
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : Number.POSITIVE_INFINITY
}

export function alignSeriesByTime(seriesByCode) {
  const entries = Object.entries(seriesByCode || {})
  const timestamps = [...new Set(entries.flatMap(([, items]) =>
    (Array.isArray(items) ? items : []).map(item => item?.collectedAt).filter(Boolean)
  ))].sort((left, right) => timestampValue(left) - timestampValue(right) || String(left).localeCompare(String(right)))

  const values = Object.fromEntries(entries.map(([code, items]) => {
    const byTime = new Map((Array.isArray(items) ? items : []).map(item => [item?.collectedAt, item?.value]))
    return [code, timestamps.map(timestamp => byTime.has(timestamp) ? byTime.get(timestamp) : null)]
  }))

  return { timestamps, values }
}
