import test from 'node:test'
import assert from 'node:assert/strict'
import { auditActionLabels, formatAuditDetail, metricLabel, roleLabels, statusLabels, statusTone, typeLabels } from '../src/format.js'

test('business dictionaries expose expected labels', () => {
  assert.equal(typeLabels.TOWER_CRANE, '塔吊')
  assert.equal(roleLabels.DEVICE_MANAGER, '设备管理员')
  assert.equal(statusLabels.PENDING, '待确认')
  assert.equal(metricLabel('windSpeed'), '风速')
  assert.equal(metricLabel('INTERNAL_METRIC'), '未识别指标')
  assert.equal(auditActionLabels.SPRINKLER_TASK_TIMEOUT, '喷淋任务超时')
  assert.equal(formatAuditDetail('角色 SUPERVISOR，连接状态 ONLINE，启用状态 true'), '角色 项目主管，连接状态 在线，启用状态 启用')
})

test('status tone keeps warning and danger distinguishable', () => {
  assert.equal(statusTone('PENDING'), 'warning')
  assert.equal(statusTone('OFFLINE'), 'danger')
  assert.equal(statusTone('ONLINE'), 'success')
})
