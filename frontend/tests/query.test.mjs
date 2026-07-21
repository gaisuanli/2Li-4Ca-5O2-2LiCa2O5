import test from 'node:test'
import assert from 'node:assert/strict'
import { toQuery } from '../src/api.js'

test('query helper removes empty values and encodes text', () => {
  assert.equal(toQuery({ siteId: 1, status: '', keyword: '塔吊 1' }), '?siteId=1&keyword=%E5%A1%94%E5%90%8A+1')
})
