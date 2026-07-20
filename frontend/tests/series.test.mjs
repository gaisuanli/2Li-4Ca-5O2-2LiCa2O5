import test from 'node:test'
import assert from 'node:assert/strict'
import { alignSeriesByTime } from '../src/series.js'

test('time series are aligned by collectedAt instead of array index', () => {
  const result = alignSeriesByTime({
    windSpeed: [
      { collectedAt: '2026-07-20T10:00:00Z', value: 4 },
      { collectedAt: '2026-07-20T10:02:00Z', value: 6 }
    ],
    weight: [
      { collectedAt: '2026-07-20T10:01:00Z', value: 20 },
      { collectedAt: '2026-07-20T10:02:00Z', value: 22 }
    ]
  })

  assert.deepEqual(result.timestamps, [
    '2026-07-20T10:00:00Z',
    '2026-07-20T10:01:00Z',
    '2026-07-20T10:02:00Z'
  ])
  assert.deepEqual(result.values.windSpeed, [4, null, 6])
  assert.deepEqual(result.values.weight, [null, 20, 22])
})

test('empty and invalid series produce a stable empty result', () => {
  assert.deepEqual(alignSeriesByTime({ windSpeed: [], weight: null }), {
    timestamps: [],
    values: { windSpeed: [], weight: [] }
  })
})
