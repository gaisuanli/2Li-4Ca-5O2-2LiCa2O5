import test from 'node:test'
import assert from 'node:assert/strict'
import { buildSprinklerAck } from '../src/sprinkler.js'

test('successful sprinkler demo acknowledgements do not carry a failure reason', () => {
  assert.deepEqual(buildSprinklerAck(true, 'ignored'), { success: true })
})

test('failed sprinkler demo acknowledgements require and normalize a reason', () => {
  assert.deepEqual(buildSprinklerAck(false, '  网关无响应  '), {
    success: false,
    failureReason: '网关无响应'
  })
  assert.throws(() => buildSprinklerAck(false, '  '), /必须填写失败原因/)
  assert.throws(() => buildSprinklerAck(false, 'x'.repeat(501)), /不能超过 500/)
})
