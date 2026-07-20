export function encodeFrame(payload) {
  const json = Buffer.from(JSON.stringify(payload), 'utf8')
  const frame = Buffer.allocUnsafe(json.length + 4)
  frame.writeInt32BE(json.length, 0)
  json.copy(frame, 4)
  return frame
}

export function decodeFrames(buffer) {
  const messages = []
  let offset = 0
  while (buffer.length - offset >= 4) {
    const length = buffer.readInt32BE(offset)
    if (length <= 0 || length > 1024 * 1024) throw new Error('非法 TCP 帧长度')
    if (buffer.length - offset - 4 < length) break
    messages.push(JSON.parse(buffer.subarray(offset + 4, offset + 4 + length).toString('utf8')))
    offset += 4 + length
  }
  return { messages, remainder: buffer.subarray(offset) }
}

export function parseCsv(text) {
  const lines = text.replace(/^\uFEFF/, '').trim().split(/\r?\n/)
  const headers = lines.shift().split(',')
  return lines.map(line => Object.fromEntries(line.split(',').map((value, index) => [headers[index], value])))
}

export const simulatedDeviceCodes = Object.freeze([
  'TC-001',
  'TC-002',
  'EL-001',
  'FM-001',
  'PIT-001'
])

const largeEquipmentProfiles = Object.freeze({
  'EL-001': Object.freeze({
    load: ['kg', [980, 1380, 1760, 1920]],
    floor: ['层', [3, 6, 9, 12]],
    speed: ['m/s', [0.8, 1.1, 1.3, 1.5]],
    direction: ['code', [2, 2, 2, 1]],
    doorStatus: ['code', [0, 0, 0, 1]],
    limitStatus: ['code', [0, 0, 0, 1]]
  }),
  'FM-001': Object.freeze({
    axialForce: ['kN', [121, 126, 132, 138]],
    displacement: ['mm', [0.8, 0.9, 1.1, 1.2]],
    settlement: ['mm', [0.5, 0.6, 0.7, 0.9]],
    xAngle: ['°', [0.08, 0.09, 0.11, 0.12]],
    yAngle: ['°', [-0.05, -0.04, -0.02, -0.01]],
    pressure: ['kPa', [23, 24, 26, 28]]
  }),
  'PIT-001': Object.freeze({
    horizontalDisplacement: ['mm', [4.2, 4.6, 5.1, 5.8]],
    settlement: ['mm', [2.1, 2.3, 2.6, 2.9]],
    axialForce: ['kN', [240, 248, 256, 266]],
    xAngle: ['°', [0.10, 0.12, 0.14, 0.15]],
    yAngle: ['°', [0.04, 0.05, 0.06, 0.07]],
    waterLevel: ['m', [-3.2, -3.0, -2.8, -2.6]],
    earthPressure: ['kPa', [48, 50, 52, 54]],
    strain: ['με', [115, 123, 132, 141]],
    settlementRate: ['mm/h', [0.18, 0.22, 0.26, 0.28]]
  })
})

export function telemetryFromRow(row, messageId, collectedAt = new Date().toISOString(), deviceCode = 'TC-001') {
  return {
    protocolVersion: '1.0',
    messageId,
    deviceCode,
    messageType: 'telemetry',
    collectedAt,
    metrics: [
      { code: 'weight', value: Number(row.weight), unit: 't' },
      { code: 'amplitude', value: Number(row.amplitude), unit: 'm' },
      { code: 'obliquity', value: Number(row.obliguity), unit: '°' },
      { code: 'windSpeed', value: Number(row.windSpeed), unit: 'm/s' },
      { code: 'moment', value: Number(row.moment), unit: 't·m' },
      { code: 'height', value: Number(row.height), unit: 'm' },
      { code: 'rotation', value: Number(row.rotation), unit: '°' }
    ]
  }
}

export function telemetryForDevice(deviceCode, sampleIndex, towerRow, messageId, collectedAt = new Date().toISOString()) {
  if (deviceCode === 'TC-001' || deviceCode === 'TC-002') {
    return telemetryFromRow(towerRow, messageId, collectedAt, deviceCode)
  }
  const profile = largeEquipmentProfiles[deviceCode]
  if (!profile) throw new Error(`未定义的模拟设备：${deviceCode}`)
  const normalizedIndex = Math.floor(Math.max(0, Number(sampleIndex) || 0))
  const metrics = Object.entries(profile).map(([code, [unit, values]]) => ({
    code,
    value: values[normalizedIndex % values.length],
    unit
  }))
  return {
    protocolVersion: '1.0',
    messageId,
    deviceCode,
    messageType: 'telemetry',
    collectedAt,
    metrics
  }
}
