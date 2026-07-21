import test from 'node:test'
import assert from 'node:assert/strict'
import {
  agentModeLabel,
  getComposerRows,
  normalizeAgentConfig,
  normalizeAgentMessage,
  normalizeAgentPage,
  normalizeConversation,
  prepareAgentInput
} from '../src/agent.js'

test('agent config exposes only the supported backend modes', () => {
  assert.deepEqual(normalizeAgentConfig({ mode: 'openai_compatible', model: 'qwen-plus', available: true, maxContentChars: 3200 }), {
    mode: 'OPENAI_COMPATIBLE', available: true, enabled: true, model: 'qwen-plus', message: '', configured: true, maxContentChars: 3200
  })
  assert.equal(normalizeAgentConfig({ mode: 'unknown', enabled: true }).mode, 'DISABLED')
  assert.equal(normalizeAgentConfig({ mode: 'DEMO', enabled: false }).enabled, false)
  assert.equal(normalizeAgentConfig({ mode: 'DEMO' }).maxContentChars, 8000)
  assert.equal(agentModeLabel('demo'), '演示模式')
})

test('agent pages accept common server pagination field names', () => {
  const page = normalizeAgentPage({ content: [{ conversationId: 9, name: '巡检' }], number: 1, size: 10, totalElements: 21 }, 1, 20, normalizeConversation)
  assert.equal(page.page, 2)
  assert.equal(page.pageSize, 10)
  assert.equal(page.total, 21)
  assert.deepEqual(page.items[0], { conversationId: 9, name: '巡检', id: 9, title: '巡检', createdAt: null, updatedAt: null })
})

test('agent messages normalize roles without inventing answer content', () => {
  assert.deepEqual(normalizeAgentMessage({ messageId: 'm1', sender: 'bot', text: '现场回答' }), {
    messageId: 'm1', sender: 'bot', text: '现场回答', id: 'm1', role: 'ASSISTANT', content: '现场回答', createdAt: null
  })
  assert.equal(normalizeAgentMessage({ sender: 'assistant' }).content, '')
})

test('composer validates input and grows within PC layout bounds', () => {
  assert.equal(prepareAgentInput('   ').error, '请输入问题后再发送。')
  assert.deepEqual(prepareAgentInput('  塔吊当前是否正常？  '), { content: '塔吊当前是否正常？', error: '' })
  assert.match(prepareAgentInput('12345', 4).error, /4/)
  assert.equal(getComposerRows('一行'), 3)
  assert.equal(getComposerRows(Array.from({ length: 20 }, () => '一行').join('\n')), 8)
})
