import test from 'node:test'
import assert from 'node:assert/strict'
import {
  channelReadyForDelivery,
  governanceStatusLabel,
  governanceStatusTone,
  knowledgeActions,
  reportActions,
  reportTemplateFields
} from '../src/governance.js'

test('governance status and role actions follow the backend state machines', () => {
  assert.equal(governanceStatusLabel('PENDING_REVIEW'), '待审核')
  assert.equal(governanceStatusTone('APPROVED'), 'success')
  assert.deepEqual(knowledgeActions('PENDING_REVIEW', false), [])
  assert.deepEqual(knowledgeActions('PENDING_REVIEW', true), ['REVIEW'])
  assert.deepEqual(reportActions('APPROVED', false), ['DELIVER'])
})

test('delivery readiness and template fields stay explicitly controlled', () => {
  assert.equal(channelReadyForDelivery({ enabled: true, runtimeReady: true, credentialConfigured: true }), true)
  assert.equal(channelReadyForDelivery({ enabled: true, runtimeReady: false, credentialConfigured: true }), false)
  assert.deepEqual(reportTemplateFields(), [
    'siteName', 'generatedAt', 'deviceTotal', 'onlineDeviceTotal',
    'activeAlarmTotal', 'highAlarmTotal', 'pendingRiskTotal', 'pendingSprinklerTotal'
  ])
})
