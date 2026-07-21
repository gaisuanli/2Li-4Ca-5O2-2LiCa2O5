const AGENT_MODES = new Set(['DEMO', 'OPENAI_COMPATIBLE', 'DISABLED'])

function asObject(value) {
  return value && typeof value === 'object' && !Array.isArray(value) ? value : {}
}

function asId(value) {
  const numeric = Number(value)
  return Number.isFinite(numeric) && numeric > 0 ? numeric : value ?? null
}

function asText(value, fallback = '') {
  return typeof value === 'string' && value.trim() ? value.trim() : fallback
}

export function normalizeAgentMode(value) {
  const mode = String(value || '').trim().toUpperCase()
  return AGENT_MODES.has(mode) ? mode : 'DISABLED'
}

export function normalizeAgentConfig(value) {
  const source = asObject(value)
  const mode = normalizeAgentMode(source.mode ?? source.providerMode ?? source.providerType ?? source.type)
  const maxContentChars = Number(source.maxContentChars)
  const available = source.available ?? source.enabled ?? mode !== 'DISABLED'
  return {
    mode,
    available: Boolean(available) && mode !== 'DISABLED',
    enabled: Boolean(available) && mode !== 'DISABLED',
    model: asText(source.model ?? source.modelName ?? source.defaultModel, '未配置模型'),
    message: asText(source.message ?? source.statusMessage ?? source.reason),
    configured: source.configured !== false && mode !== 'DISABLED',
    maxContentChars: Number.isFinite(maxContentChars) && maxContentChars > 0 ? maxContentChars : 8000
  }
}

export function normalizeConversation(value) {
  const source = asObject(value)
  return {
    ...source,
    id: asId(source.id ?? source.conversationId),
    title: asText(source.title ?? source.name, '新会话'),
    createdAt: source.createdAt ?? source.createdTime ?? null,
    updatedAt: source.updatedAt ?? source.updatedTime ?? source.lastMessageAt ?? null
  }
}

export function normalizeAgentMessage(value) {
  const source = asObject(value)
  const rawRole = String(source.role ?? source.sender ?? source.type ?? '').toUpperCase()
  const role = ['ASSISTANT', 'AI', 'BOT'].includes(rawRole) ? 'ASSISTANT' : rawRole === 'SYSTEM' ? 'SYSTEM' : 'USER'
  return {
    ...source,
    id: source.id ?? source.messageId ?? null,
    role,
    content: typeof (source.content ?? source.message ?? source.text) === 'string'
      ? (source.content ?? source.message ?? source.text)
      : '',
    createdAt: source.createdAt ?? source.createdTime ?? source.timestamp ?? null
  }
}

export function normalizeAgentPage(value, fallbackPage = 1, fallbackPageSize = 20, itemNormalizer = item => item) {
  const source = asObject(value)
  const rawItems = Array.isArray(value)
    ? value
    : source.items ?? source.content ?? source.records ?? source.list ?? []
  const items = Array.isArray(rawItems) ? rawItems.map(itemNormalizer) : []
  const page = Number(source.page ?? source.number ?? source.pageNumber ?? fallbackPage)
  const pageSize = Number(source.pageSize ?? source.size ?? fallbackPageSize)
  const total = Number(source.total ?? source.totalElements ?? source.totalCount ?? items.length)
  return {
    items,
    page: Number.isFinite(page) ? Math.max(1, source.number !== undefined ? page + 1 : page) : fallbackPage,
    pageSize: Number.isFinite(pageSize) && pageSize > 0 ? pageSize : fallbackPageSize,
    total: Number.isFinite(total) && total >= 0 ? total : items.length
  }
}

export function prepareAgentInput(value, maxLength = 8000) {
  const content = typeof value === 'string' ? value.trim() : ''
  if (!content) return { content: '', error: '请输入问题后再发送。' }
  if (content.length > maxLength) return { content, error: `问题不能超过 ${maxLength} 个字符。` }
  return { content, error: '' }
}

export function getComposerRows(value, minRows = 3, maxRows = 8, lineLength = 72) {
  const lines = String(value || '').split('\n')
  const visualLines = lines.reduce((total, line) => total + Math.max(1, Math.ceil(line.length / lineLength)), 0)
  return Math.min(maxRows, Math.max(minRows, visualLines))
}

export function agentModeLabel(mode) {
  return {
    DEMO: '演示模式',
    OPENAI_COMPATIBLE: '兼容 API',
    DISABLED: '未启用'
  }[normalizeAgentMode(mode)]
}
