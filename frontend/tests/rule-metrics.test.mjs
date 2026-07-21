import test from 'node:test'
import assert from 'node:assert/strict'
import {
  compatibleRuleDevices,
  isSharedRuleMetric,
  metricsForRuleDeviceType,
  normalizedRuleScope,
  ruleDeviceTypesForMetric,
  sourceTypeForRuleMetric
} from '../src/rule-metrics.js'

test('large-equipment rule metrics map to the same device families as the backend catalog', () => {
  assert.ok(metricsForRuleDeviceType('ELEVATOR').includes('limitStatus'))
  assert.ok(metricsForRuleDeviceType('FORMWORK').includes('pressure'))
  assert.ok(metricsForRuleDeviceType('FOUNDATION_PIT').includes('settlementRate'))
  assert.deepEqual(ruleDeviceTypesForMetric('axialForce'), ['FORMWORK', 'FOUNDATION_PIT'])
  assert.equal(sourceTypeForRuleMetric('pm25'), 'ENVIRONMENT_RULE')
})

test('shared metrics force device scope and only keep compatible devices', () => {
  assert.equal(isSharedRuleMetric('settlement'), true)
  assert.equal(normalizedRuleScope('settlement', 'TYPE'), 'DEVICE')
  assert.equal(normalizedRuleScope('pressure', 'TYPE'), 'TYPE')
  const devices = [
    { id: 1, type: 'FORMWORK' },
    { id: 2, type: 'FOUNDATION_PIT' },
    { id: 3, type: 'TOWER_CRANE' }
  ]
  assert.deepEqual(compatibleRuleDevices(devices, 'FORMWORK', 'axialForce').map(item => item.id), [1])
})
