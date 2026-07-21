import test from 'node:test'
import assert from 'node:assert/strict'
import {
  equipmentMetricUsesUnit,
  formatEquipmentMetricValue,
  metricsForEquipmentType,
  resolveEquipmentMetric
} from '../src/equipment.js'

test('large-equipment profiles expose only their declared metrics', () => {
  assert.deepEqual(metricsForEquipmentType('ELEVATOR'), ['load', 'floor', 'speed', 'direction', 'doorStatus', 'limitStatus'])
  assert.equal(metricsForEquipmentType('FORMWORK').includes('axialForce'), true)
  assert.equal(metricsForEquipmentType('FOUNDATION_PIT').includes('horizontalDisplacement'), true)
  assert.deepEqual(metricsForEquipmentType('CAMERA'), [])
})

test('metric selection preserves an available preference and otherwise uses available data', () => {
  assert.equal(resolveEquipmentMetric('ELEVATOR', ['load', 'speed'], 'speed'), 'speed')
  assert.equal(resolveEquipmentMetric('ELEVATOR', ['floor'], 'speed'), 'floor')
  assert.equal(resolveEquipmentMetric('FORMWORK', [], 'pressure'), 'axialForce')
})

test('coded elevator metrics render semantic text without a physical unit', () => {
  assert.equal(formatEquipmentMetricValue('direction', 2), '上行')
  assert.equal(formatEquipmentMetricValue('doorStatus', 0), '关闭')
  assert.equal(formatEquipmentMetricValue('limitStatus', 1), '触发')
  assert.equal(equipmentMetricUsesUnit('direction'), false)
  assert.equal(equipmentMetricUsesUnit('load'), true)
})

test('missing and numeric metric values remain explicit and deterministic', () => {
  assert.equal(formatEquipmentMetricValue('load', null), '—')
  assert.equal(formatEquipmentMetricValue('settlement', 2.345), '2.35')
  assert.equal(formatEquipmentMetricValue('direction', 8), '未知状态（8）')
})
