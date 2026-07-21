export const RULE_METRIC_PROFILES = Object.freeze({
  TOWER_CRANE: ['windSpeed', 'weight', 'rotation', 'height', 'amplitude', 'moment', 'obliquity'],
  ELEVATOR: ['load', 'floor', 'speed', 'direction', 'doorStatus', 'limitStatus'],
  FORMWORK: ['axialForce', 'displacement', 'settlement', 'xAngle', 'yAngle', 'pressure'],
  FOUNDATION_PIT: ['horizontalDisplacement', 'settlement', 'axialForce', 'xAngle', 'yAngle', 'waterLevel', 'earthPressure', 'strain', 'settlementRate'],
  ENVIRONMENT: ['pm25', 'pm10', 'noise', 'temperature', 'humidity']
})

export const RULE_DEVICE_TYPES = Object.freeze(Object.keys(RULE_METRIC_PROFILES))
export const RULE_METRICS = Object.freeze([...new Set(Object.values(RULE_METRIC_PROFILES).flat())])

export function metricsForRuleDeviceType(deviceType) {
  return RULE_METRIC_PROFILES[deviceType] || []
}

export function ruleDeviceTypesForMetric(metricCode) {
  return RULE_DEVICE_TYPES.filter(type => RULE_METRIC_PROFILES[type].includes(metricCode))
}

export function isSharedRuleMetric(metricCode) {
  return ruleDeviceTypesForMetric(metricCode).length > 1
}

export function sourceTypeForRuleMetric(metricCode) {
  return ruleDeviceTypesForMetric(metricCode).length === 1
    && ruleDeviceTypesForMetric(metricCode)[0] === 'ENVIRONMENT'
    ? 'ENVIRONMENT_RULE'
    : 'DEVICE_RULE'
}

export function compatibleRuleDevices(devices, deviceType, metricCode) {
  const supportedTypes = ruleDeviceTypesForMetric(metricCode)
  return (devices || []).filter(device => (
    device.type === deviceType && supportedTypes.includes(device.type)
  ))
}

export function normalizedRuleScope(metricCode, requestedScope) {
  return isSharedRuleMetric(metricCode) ? 'DEVICE' : requestedScope
}
