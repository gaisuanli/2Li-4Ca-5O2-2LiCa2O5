import test from 'node:test'
import assert from 'node:assert/strict'
import {
  imageBase64FromDataUrl,
  integrationFacts,
  integrationStateLabel,
  integrationStateTone
} from '../src/integrations.js'

test('integration states preserve truthful configuration and health meanings', () => {
  assert.equal(integrationStateLabel('NOT_CONFIGURED'), '未配置')
  assert.equal(integrationStateLabel('SIMULATED'), '模拟模式')
  assert.equal(integrationStateTone('READY'), 'success')
  assert.equal(integrationStateTone('MISCONFIGURED'), 'danger')
})

test('image payload extraction and fact projection omit control fields', () => {
  assert.equal(imageBase64FromDataUrl('data:image/png;base64,aW1hZ2U='), 'aW1hZ2U=')
  assert.equal(imageBase64FromDataUrl('not-a-data-url'), '')
  assert.deepEqual(integrationFacts({ type: 'VIDEO', name: '真实视频', state: 'READY', totalChannels: 3 }), [
    { key: 'totalChannels', value: 3 }
  ])
})
