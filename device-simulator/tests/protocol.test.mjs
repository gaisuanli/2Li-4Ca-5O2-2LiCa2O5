import test from 'node:test'
import assert from 'node:assert/strict'
import {
  decodeFrames,
  encodeFrame,
  parseCsv,
  simulatedDeviceCodes,
  telemetryForDevice,
  telemetryFromRow
} from '../protocol.js'

test('length-prefixed frame round trips', () => {
  const payload = { protocolVersion: '1.0', messageId: 'TEST-1', metrics: [{ code: 'windSpeed', value: 7.2 }] }
  const decoded = decodeFrames(encodeFrame(payload))
  assert.deepEqual(decoded.messages, [payload])
  assert.equal(decoded.remainder.length, 0)
})

test('partial frame remains buffered', () => {
  const frame = encodeFrame({ ok: true })
  const decoded = decodeFrames(frame.subarray(0, 6))
  assert.equal(decoded.messages.length, 0)
  assert.equal(decoded.remainder.length, 6)
})

test('csv row maps to deterministic tower metrics', () => {
  const [row] = parseCsv('monitorTime,weight,amplitude,obliguity,windSpeed,moment,height,rotation,安全状态\n2025-01-01,20,30,0.4,6.2,800,50,90,安全')
  const telemetry = telemetryFromRow(row, 'TEST-2', '2026-07-19T08:00:00.000Z')
  assert.equal(telemetry.deviceCode, 'TC-001')
  assert.equal(telemetry.metrics.find(metric => metric.code === 'weight').value, 20)
  assert.equal(telemetry.metrics.length, 7)
})

test('multi-device profiles provide complete deterministic metric sets', () => {
  const [row] = parseCsv('monitorTime,weight,amplitude,obliguity,windSpeed,moment,height,rotation,安全状态\n2025-01-01,20,30,0.4,6.2,800,50,90,安全')
  const expectedMetricCounts = { 'TC-001': 7, 'TC-002': 7, 'EL-001': 6, 'FM-001': 6, 'PIT-001': 9 }
  for (const deviceCode of simulatedDeviceCodes) {
    const payload = telemetryForDevice(deviceCode, 3, row, `TEST-${deviceCode}`, '2026-07-19T08:00:00.000Z')
    assert.equal(payload.deviceCode, deviceCode)
    assert.equal(payload.metrics.length, expectedMetricCounts[deviceCode])
    assert.ok(payload.metrics.every(metric => Number.isFinite(metric.value)))
  }
  assert.equal(telemetryForDevice('EL-001', 3, row, 'TEST-EL').metrics.find(metric => metric.code === 'limitStatus').value, 1)
  assert.equal(telemetryForDevice('FM-001', 3, row, 'TEST-FM').metrics.find(metric => metric.code === 'pressure').value, 28)
  assert.equal(telemetryForDevice('PIT-001', 3, row, 'TEST-PIT').metrics.find(metric => metric.code === 'settlementRate').value, 0.28)
})

test('unknown simulated device is rejected before a TCP frame is sent', () => {
  assert.throws(() => telemetryForDevice('UNKNOWN-001', 0, {}, 'TEST-UNKNOWN'), /未定义的模拟设备/)
})
