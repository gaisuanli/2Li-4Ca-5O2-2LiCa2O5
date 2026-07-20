function asObject(value) {
  return value && typeof value === 'object' && !Array.isArray(value) ? value : {}
}

function asText(value) {
  return typeof value === 'string' ? value.trim() : ''
}

function asTextList(value) {
  if (!Array.isArray(value)) return []
  return [...new Set(value.map(asText).filter(Boolean))]
}

export function normalizeAgentProviderConfig(value) {
  const source = asObject(value)
  const apiKeyConfigured = Boolean(source.apiKeyConfigured ?? source.credentialConfigured)
  const credentialStorageAvailable = source.credentialStorageAvailable !== false
  const userConfigEnabled = source.userConfigEnabled ?? source.configurationEnabled ?? true
  const rawEffectiveMode = asText(source.effectiveMode).toUpperCase()
  const effectiveMode = ['DEMO', 'OPENAI_COMPATIBLE', 'DISABLED'].includes(rawEffectiveMode)
    ? rawEffectiveMode
    : ''
  return {
    configured: Boolean(source.configured),
    baseUrl: asText(source.baseUrl),
    model: asText(source.model),
    apiKeyConfigured,
    credentialStorageAvailable,
    userConfigEnabled: Boolean(userConfigEnabled),
    available: Boolean(source.available ?? (apiKeyConfigured && credentialStorageAvailable && userConfigEnabled)),
    effectiveMode,
    effectiveModel: asText(source.effectiveModel),
    approvedBaseUrls: asTextList(source.approvedBaseUrls ?? source.allowedBaseUrls),
    approvedModels: asTextList(source.approvedModels),
    customModelAllowed: source.customModelAllowed !== false
  }
}

export function createAgentProviderForm(config = {}) {
  const normalized = normalizeAgentProviderConfig(config)
  return {
    baseUrl: normalized.baseUrl,
    model: normalized.model,
    apiKey: ''
  }
}

export function prepareAgentProviderPayload(form, config = {}) {
  const source = asObject(form)
  const normalized = normalizeAgentProviderConfig(config)
  const baseUrl = asText(source.baseUrl)
  const model = asText(source.model)
  const apiKey = typeof source.apiKey === 'string' ? source.apiKey.trim() : ''

  if (!normalized.userConfigEnabled) {
    return { payload: null, error: '管理员已停用用户自定义模型配置。' }
  }
  if (!normalized.credentialStorageAvailable) {
    return { payload: null, error: '后端密钥存储当前不可用，无法安全保存配置。' }
  }
  if (!baseUrl) return { payload: null, error: '请输入 Base URL。' }
  if (!/^https?:\/\//i.test(baseUrl)) return { payload: null, error: 'Base URL 必须使用 HTTP 或 HTTPS 协议。' }
  if (normalized.approvedBaseUrls.length && !normalized.approvedBaseUrls.includes(baseUrl)) {
    return { payload: null, error: 'Base URL 不在后端批准名单中。' }
  }
  if (!model) return { payload: null, error: '请输入或选择模型。' }
  if (normalized.approvedModels.length && !normalized.approvedModels.includes(model)) {
    return { payload: null, error: '模型不在后端批准名单中。' }
  }
  if (!apiKey && !normalized.apiKeyConfigured) {
    return { payload: null, error: '首次配置时必须输入 API Key。' }
  }

  const payload = { baseUrl, model }
  if (apiKey) payload.apiKey = apiKey
  return { payload, error: '' }
}

export function agentProviderStatus(config = {}) {
  const normalized = normalizeAgentProviderConfig(config)
  if (!normalized.userConfigEnabled) return { code: 'DISABLED', label: '管理员已停用' }
  if (!normalized.credentialStorageAvailable) return { code: 'STORAGE_UNAVAILABLE', label: '密钥存储不可用' }
  if (!normalized.apiKeyConfigured) return { code: 'NOT_CONFIGURED', label: '等待配置' }
  if (!normalized.available) return { code: 'SAVED', label: '已保存，当前不可用' }
  return { code: 'READY', label: '配置可用' }
}

export function agentProviderRoute(config = {}) {
  const normalized = normalizeAgentProviderConfig(config)
  if (normalized.effectiveMode === 'DEMO') {
    return { active: normalized.available, label: normalized.available ? '本地演示' : '演示不可用' }
  }
  if (normalized.effectiveMode === 'OPENAI_COMPATIBLE') {
    return { active: normalized.available, label: normalized.available ? '第三方服务' : '第三方不可用' }
  }
  if (normalized.effectiveMode === 'DISABLED') return { active: false, label: '已停用' }
  return { active: false, label: '未就绪' }
}
