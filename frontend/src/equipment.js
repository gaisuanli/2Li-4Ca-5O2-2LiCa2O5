export const equipmentTypes = ['ELEVATOR', 'FORMWORK', 'FOUNDATION_PIT']

export const equipmentMetricCodes = {
  ELEVATOR: ['load', 'floor', 'speed', 'direction', 'doorStatus', 'limitStatus'],
  FORMWORK: ['axialForce', 'displacement', 'settlement', 'xAngle', 'yAngle', 'pressure'],
  FOUNDATION_PIT: ['horizontalDisplacement', 'settlement', 'axialForce', 'xAngle', 'yAngle', 'waterLevel', 'earthPressure', 'strain', 'settlementRate']
}

const codedMetricLabels = {
  direction: { 0: '未知', 1: '静止', 2: '上行', 3: '下行' },
  doorStatus: { 0: '关闭', 1: '开启' },
  limitStatus: { 0: '正常', 1: '触发' }
}

export function metricsForEquipmentType(type) {
  return equipmentMetricCodes[type] || []
}

export function resolveEquipmentMetric(type, availableCodes = [], preferredCode = '') {
  const configured = metricsForEquipmentType(type)
  if (preferredCode && configured.includes(preferredCode) && availableCodes.includes(preferredCode)) return preferredCode
  return configured.find(code => availableCodes.includes(code)) || configured[0] || ''
}

export function formatEquipmentMetricValue(code, value) {
  if (value === null || value === undefined || value === '') return '—'
  const labels = codedMetricLabels[code]
  if (labels) return labels[Number(value)] || `未知状态（${value}）`
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) return String(value)
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(numeric)
}

export function equipmentMetricUsesUnit(code) {
  return !codedMetricLabels[code]
}
