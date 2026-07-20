const INTEGRATION_STATE_LABELS = {
  NOT_CONFIGURED: '未配置',
  CONFIGURED: '已配置',
  SIMULATED: '模拟模式',
  READY: '就绪',
  DEGRADED: '部分可用',
  DOWN: '不可用',
  DISABLED: '已停用',
  MISCONFIGURED: '配置无效',
  REACHABLE: '端点可达',
  UNREACHABLE: '端点不可达'
}

export function integrationStateLabel(state) {
  return INTEGRATION_STATE_LABELS[state] || '未知状态'
}

export function integrationStateTone(state) {
  if (['READY', 'REACHABLE'].includes(state)) return 'success'
  if (['CONFIGURED', 'SIMULATED', 'DEGRADED'].includes(state)) return 'warning'
  if (['DOWN', 'MISCONFIGURED', 'UNREACHABLE'].includes(state)) return 'danger'
  return 'neutral'
}

export function imageBase64FromDataUrl(dataUrl) {
  const text = typeof dataUrl === 'string' ? dataUrl : ''
  const marker = ';base64,'
  const index = text.indexOf(marker)
  return index >= 0 ? text.slice(index + marker.length) : ''
}

export function integrationFacts(item = {}) {
  const omitted = new Set(['type', 'name', 'state', 'message', 'checks', 'checkedAt'])
  return Object.entries(item)
    .filter(([key, value]) => !omitted.has(key) && value !== null && value !== undefined && value !== '')
    .map(([key, value]) => ({ key, value }))
}
