import test from 'node:test'
import assert from 'node:assert/strict'
import {
  agentProviderRoute,
  agentProviderStatus,
  createAgentProviderForm,
  normalizeAgentProviderConfig,
  prepareAgentProviderPayload
} from '../src/agent-provider.js'

test('provider config keeps public metadata and drops every secret field', () => {
  const normalized = normalizeAgentProviderConfig({
    baseUrl: ' https://api.example.com/v1 ',
    model: ' safety-model ',
    apiKeyConfigured: true,
    credentialStorageAvailable: true,
    userConfigEnabled: true,
    available: true,
    approvedBaseUrls: ['https://api.example.com/v1', 'https://api.example.com/v1', ''],
    approvedModels: ['safety-model', 'safety-model'],
    apiKey: 'must-not-escape',
    encryptedApiKey: 'ciphertext',
    credentialReference: 'secret/ref'
  })

  assert.deepEqual(normalized, {
    configured: false,
    baseUrl: 'https://api.example.com/v1',
    model: 'safety-model',
    apiKeyConfigured: true,
    credentialStorageAvailable: true,
    userConfigEnabled: true,
    available: true,
    effectiveMode: '',
    effectiveModel: '',
    approvedBaseUrls: ['https://api.example.com/v1'],
    approvedModels: ['safety-model'],
    customModelAllowed: true
  })
  for (const secretField of ['apiKey', 'encryptedApiKey', 'credentialReference']) {
    assert.equal(Object.hasOwn(normalized, secretField), false)
  }
  assert.equal(Object.values(normalized).includes('must-not-escape'), false)
  assert.equal(Object.values(normalized).includes('ciphertext'), false)
  assert.deepEqual(createAgentProviderForm(normalized), {
    baseUrl: 'https://api.example.com/v1',
    model: 'safety-model',
    apiKey: ''
  })
})

test('provider payload omits a blank key so an existing credential is retained', () => {
  const config = normalizeAgentProviderConfig({
    apiKeyConfigured: true,
    approvedBaseUrls: ['https://api.example.com/v1'],
    approvedModels: ['model-a']
  })
  const result = prepareAgentProviderPayload({
    baseUrl: ' https://api.example.com/v1 ',
    model: ' model-a ',
    apiKey: '   '
  }, config)

  assert.deepEqual(result, {
    payload: { baseUrl: 'https://api.example.com/v1', model: 'model-a' },
    error: ''
  })
  assert.equal(Object.hasOwn(result.payload, 'apiKey'), false)
})

test('provider payload sends a newly entered key but never invents provider options', () => {
  const config = normalizeAgentProviderConfig({
    apiKeyConfigured: false,
    approvedBaseUrls: ['https://gateway.example/v1'],
    approvedModels: ['approved-model']
  })
  assert.deepEqual(prepareAgentProviderPayload({
    baseUrl: 'https://gateway.example/v1',
    model: 'approved-model',
    apiKey: 'new-key'
  }, config), {
    payload: { baseUrl: 'https://gateway.example/v1', model: 'approved-model', apiKey: 'new-key' },
    error: ''
  })

  assert.match(prepareAgentProviderPayload({
    baseUrl: 'https://unapproved.example/v1', model: 'approved-model', apiKey: 'new-key'
  }, config).error, /Base URL/)
  assert.match(prepareAgentProviderPayload({
    baseUrl: 'https://gateway.example/v1', model: 'invented-model', apiKey: 'new-key'
  }, config).error, /模型/)
})

test('provider status exposes global and credential-storage safety boundaries', () => {
  assert.deepEqual(agentProviderStatus({ userConfigEnabled: false }), { code: 'DISABLED', label: '管理员已停用' })
  assert.deepEqual(agentProviderStatus({ credentialStorageAvailable: false }), { code: 'STORAGE_UNAVAILABLE', label: '密钥存储不可用' })
  assert.deepEqual(agentProviderStatus({ apiKeyConfigured: false }), { code: 'NOT_CONFIGURED', label: '等待配置' })
  assert.deepEqual(agentProviderStatus({ apiKeyConfigured: true, available: true }), { code: 'READY', label: '配置可用' })
})

test('provider route distinguishes local demo from a configured third-party service', () => {
  assert.deepEqual(agentProviderRoute({ effectiveMode: 'DEMO', available: true }), { active: true, label: '本地演示' })
  assert.deepEqual(agentProviderRoute({ effectiveMode: 'OPENAI_COMPATIBLE', available: true }), { active: true, label: '第三方服务' })
  assert.deepEqual(agentProviderRoute({ effectiveMode: 'OPENAI_COMPATIBLE', available: false }), { active: false, label: '第三方不可用' })
  assert.deepEqual(agentProviderRoute({ effectiveMode: 'DISABLED', available: false }), { active: false, label: '已停用' })
})
