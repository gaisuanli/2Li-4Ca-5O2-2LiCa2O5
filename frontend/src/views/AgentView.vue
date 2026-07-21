<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { api, formatApiError, jsonBody, toQuery } from '../api'
import {
  agentModeLabel,
  getComposerRows,
  normalizeAgentConfig,
  normalizeAgentMessage,
  normalizeAgentPage,
  normalizeConversation,
  prepareAgentInput
} from '../agent'
import { normalizeAgentProviderConfig } from '../agent-provider'
import { formatDate } from '../format'
import AgentProviderPanel from '../components/agent/AgentProviderPanel.vue'
import PageState from '../components/PageState.vue'
import PaginationBar from '../components/PaginationBar.vue'

const store = useStore()
const config = ref(normalizeAgentConfig({ mode: 'DISABLED', available: false }))
const configLoading = ref(true)
const configError = ref('')
const providerConfig = ref(normalizeAgentProviderConfig({}))
const providerLoading = ref(true)
const providerBusy = ref(false)
const providerError = ref('')
const providerOperationError = ref('')
const providerNotice = ref('')
const conversations = ref([])
const conversationTotal = ref(0)
const conversationPage = ref(1)
const conversationPageSize = ref(10)
const conversationLoading = ref(true)
const conversationError = ref('')
const activeConversationId = ref(null)
const messages = ref([])
const messageTotal = ref(0)
const messagePage = ref(1)
const messagePageSize = ref(20)
const messageLoading = ref(false)
const messageError = ref('')
const composerError = ref('')
const input = ref('')
const sending = ref(false)
const composer = ref(null)
let conversationRequestSequence = 0
let messageRequestSequence = 0
let sendRequestSequence = 0

const quickQuestions = [
  '汇总当前工地尚未闭环的告警。',
  '当前有哪些设备处于离线状态？',
  '概览当前环境联动与喷淋任务。',
  '当前有多少条 AI 风险待人工复核？'
]
const activeConversation = computed(() => conversations.value.find(item => item.id === activeConversationId.value) || null)
const composerRows = computed(() => getComposerRows(input.value))
const canSend = computed(() => config.value.available && !sending.value && Boolean(input.value.trim()))
const serviceMessage = computed(() => {
  if (configLoading.value) return '正在读取后端配置'
  if (configError.value) return configError.value
  if (!config.value.available) return config.value.message || 'AI Agent 服务尚未启用'
  return config.value.mode === 'DEMO' ? '演示回答由后端生成' : '回答由已配置的兼容 API 生成'
})

async function loadConfig() {
  configLoading.value = true
  configError.value = ''
  try {
    config.value = normalizeAgentConfig(await api('/agent/config'))
  } catch (reason) {
    config.value = normalizeAgentConfig({ mode: 'DISABLED', available: false })
    configError.value = formatApiError(reason, 'AI Agent 配置读取失败')
  } finally {
    configLoading.value = false
  }
}

async function loadProviderConfig() {
  providerLoading.value = true
  providerError.value = ''
  providerOperationError.value = ''
  providerNotice.value = ''
  try {
    providerConfig.value = normalizeAgentProviderConfig(await api('/agent/provider-config'))
  } catch (reason) {
    providerConfig.value = normalizeAgentProviderConfig({})
    providerError.value = formatApiError(reason, '模型供应商配置读取失败')
  } finally {
    providerLoading.value = false
  }
}

async function saveProviderConfig(payload) {
  if (providerBusy.value) return
  providerBusy.value = true
  providerOperationError.value = ''
  providerNotice.value = ''
  try {
    providerConfig.value = normalizeAgentProviderConfig(await api('/agent/provider-config', {
      method: 'PUT',
      body: jsonBody(payload)
    }))
    providerNotice.value = '配置已安全保存。后续问答将使用该账号的第三方模型配置。'
    await loadConfig()
  } catch (reason) {
    providerOperationError.value = formatApiError(reason, '模型供应商配置保存失败')
  } finally {
    providerBusy.value = false
  }
}

async function clearProviderConfig() {
  if (providerBusy.value) return
  providerBusy.value = true
  providerOperationError.value = ''
  providerNotice.value = ''
  try {
    const payload = await api('/agent/provider-config', { method: 'DELETE' })
    providerConfig.value = normalizeAgentProviderConfig(payload || {})
    providerNotice.value = '当前账号的第三方模型配置已清除。'
    await loadConfig()
  } catch (reason) {
    providerOperationError.value = formatApiError(reason, '模型供应商配置清除失败')
  } finally {
    providerBusy.value = false
  }
}

async function loadConversations({ preserveActive = false } = {}) {
  const requestSequence = ++conversationRequestSequence
  const requestedSiteId = Number(store.state.siteId)
  const requestedPage = conversationPage.value
  const requestedPageSize = conversationPageSize.value
  const isCurrentRequest = () => requestSequence === conversationRequestSequence
    && Number(store.state.siteId) === requestedSiteId
    && conversationPage.value === requestedPage
    && conversationPageSize.value === requestedPageSize
  conversationLoading.value = true
  conversationError.value = ''
  try {
    const payload = await api(`/agent/conversations${toQuery({
      siteId: requestedSiteId,
      page: requestedPage,
      pageSize: requestedPageSize
    })}`)
    if (!isCurrentRequest()) return
    const data = normalizeAgentPage(payload, requestedPage, requestedPageSize, normalizeConversation)
    conversations.value = data.items
    conversationTotal.value = data.total
    if (!preserveActive || !activeConversationId.value) {
      await selectConversation(conversations.value[0]?.id || null)
    }
  } catch (reason) {
    if (!isCurrentRequest()) return
    conversations.value = []
    conversationTotal.value = 0
    conversationError.value = formatApiError(reason, '会话列表加载失败')
  } finally {
    if (isCurrentRequest()) conversationLoading.value = false
  }
}

async function loadMessages() {
  if (!activeConversationId.value) {
    messageRequestSequence++
    messageLoading.value = false
    messages.value = []
    messageTotal.value = 0
    return
  }
  const requestSequence = ++messageRequestSequence
  const requestedConversationId = activeConversationId.value
  const requestedSiteId = Number(store.state.siteId)
  const requestedPage = messagePage.value
  const requestedPageSize = messagePageSize.value
  const isCurrentRequest = () => requestSequence === messageRequestSequence
    && requestedConversationId === activeConversationId.value
    && Number(store.state.siteId) === requestedSiteId
    && messagePage.value === requestedPage
    && messagePageSize.value === requestedPageSize
  messageLoading.value = true
  messageError.value = ''
  try {
    const payload = await api(`/agent/conversations/${requestedConversationId}/messages${toQuery({
      page: requestedPage,
      pageSize: requestedPageSize
    })}`)
    if (!isCurrentRequest()) return
    const data = normalizeAgentPage(payload, requestedPage, requestedPageSize, normalizeAgentMessage)
    messages.value = data.items
    messageTotal.value = data.total
  } catch (reason) {
    if (isCurrentRequest()) {
      messages.value = []
      messageTotal.value = 0
      messageError.value = formatApiError(reason, '消息加载失败')
    }
  } finally {
    if (isCurrentRequest()) messageLoading.value = false
  }
}

async function selectConversation(id) {
  activeConversationId.value = id
  messagePage.value = 1
  composerError.value = ''
  input.value = ''
  await loadMessages()
}

function startNewConversation() {
  messageRequestSequence++
  messageLoading.value = false
  activeConversationId.value = null
  messages.value = []
  messageTotal.value = 0
  messagePage.value = 1
  messageError.value = ''
  composerError.value = ''
  input.value = ''
  nextTick(() => composer.value?.focus())
}

function useQuestion(question) {
  input.value = question
  nextTick(() => composer.value?.focus())
}

async function send() {
  if (!config.value.available || sending.value) return
  const prepared = prepareAgentInput(input.value, config.value.maxContentChars)
  if (prepared.error) {
    composerError.value = prepared.error
    return
  }

  const requestSequence = ++sendRequestSequence
  const requestedSiteId = Number(store.state.siteId)
  const initialConversationId = activeConversationId.value
  const isCurrentSiteRequest = () => requestSequence === sendRequestSequence
    && Number(store.state.siteId) === requestedSiteId
  sending.value = true
  composerError.value = ''
  input.value = ''
  let conversationId = initialConversationId
  try {
    if (!conversationId) {
      const created = normalizeConversation(await api('/agent/conversations', {
        method: 'POST',
        body: jsonBody({ siteId: requestedSiteId })
      }))
      conversationId = created.id
      if (isCurrentSiteRequest() && activeConversationId.value === initialConversationId) {
        activeConversationId.value = conversationId
      }
    }

    if (isCurrentSiteRequest() && activeConversationId.value === conversationId) {
      messages.value = [
        ...messages.value,
        { id: `pending-${Date.now()}`, role: 'USER', content: prepared.content, createdAt: new Date().toISOString(), pending: true }
      ]
    }
    await api(`/agent/conversations/${conversationId}/messages`, {
      method: 'POST',
      body: jsonBody({ content: prepared.content })
    })
    if (isCurrentSiteRequest()) {
      conversationPage.value = 1
      messagePage.value = 1
      await Promise.all([loadMessages(), loadConversations({ preserveActive: true })])
    }
  } catch (reason) {
    if (isCurrentSiteRequest()) {
      composerError.value = formatApiError(reason, '问题发送失败')
      input.value = prepared.content
      try { await loadMessages() } catch { /* loadMessages already exposes its own state */ }
      if (conversationId) await loadConversations({ preserveActive: true })
    }
  } finally {
    if (requestSequence === sendRequestSequence) {
      sending.value = false
      nextTick(() => composer.value?.focus())
    }
  }
}

function handleComposerKeydown(event) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) return
  event.preventDefault()
  send()
}

function changeConversationPage() {
  loadConversations({ preserveActive: false })
}

function changeMessagePage() {
  loadMessages()
}

onMounted(() => Promise.all([loadConfig(), loadProviderConfig(), loadConversations()]))
watch(() => store.state.siteId, () => {
  conversationRequestSequence++
  messageRequestSequence++
  sendRequestSequence++
  sending.value = false
  conversationPage.value = 1
  startNewConversation()
  loadConversations()
})
</script>

<template>
  <section class="agent-page">
    <header class="agent-page-heading">
      <div>
        <div class="folio"><svg viewBox="0 0 24 24"><use href="#app-icon-agent" /></svg></div>
        <h1>AI Agent 问答</h1>
        <p>会话与当前工地绑定。每个账号可选择后端批准的兼容 API；API Key 仅写入服务器密钥存储，页面不会回显。</p>
      </div>
      <div class="agent-service-summary" :class="`mode-${config.mode.toLowerCase().replace('_', '-')}`">
        <span>服务模式</span>
        <strong>{{ agentModeLabel(config.mode) }}</strong>
        <small>{{ serviceMessage }}</small>
      </div>
    </header>

    <AgentProviderPanel
      :config="providerConfig"
      :loading="providerLoading"
      :busy="providerBusy"
      :error="providerError"
      :operation-error="providerOperationError"
      :notice="providerNotice"
      @refresh="loadProviderConfig"
      @save="saveProviderConfig"
      @clear="clearProviderConfig"
    />

    <div class="agent-workspace">
      <aside class="agent-conversations" aria-label="会话历史">
        <div class="agent-conversation-head">
          <div><strong>会话历史</strong><span>当前工地 · {{ conversationTotal }} 个</span></div>
          <button class="button button-primary button-small" type="button" :disabled="sending" @click="startNewConversation">新建会话</button>
        </div>

        <div v-if="conversationLoading" class="agent-list-state" role="status">正在加载会话</div>
        <PageState v-else-if="conversationError" title="会话加载失败" :message="conversationError" action="重试" @action="loadConversations" />
        <div v-else class="agent-conversation-list">
          <button
            v-for="(conversation, index) in conversations"
            :key="conversation.id"
            type="button"
            :class="{ active: conversation.id === activeConversationId }"
            :aria-pressed="conversation.id === activeConversationId"
            :disabled="sending"
            @click="selectConversation(conversation.id)"
          >
            <span class="agent-conversation-index">{{ String((conversationPage - 1) * conversationPageSize + index + 1).padStart(2, '0') }}</span>
            <span><strong>{{ conversation.title }}</strong><small>{{ formatDate(conversation.updatedAt || conversation.createdAt) }}</small></span>
          </button>
          <div v-if="!conversations.length" class="agent-list-state">当前工地还没有会话</div>
        </div>

        <PaginationBar
          v-if="conversationTotal && !sending"
          v-model:page="conversationPage"
          v-model:page-size="conversationPageSize"
          :total="conversationTotal"
          :page-sizes="[10, 20]"
          aria-label="AI 会话分页"
          @change="changeConversationPage"
        />
      </aside>

      <section class="agent-chat" aria-label="AI Agent 对话">
        <header class="agent-chat-head">
          <div>
            <span>{{ activeConversation ? '当前会话' : '新会话' }}</span>
            <strong>{{ activeConversation?.title || '提出与工地安全相关的问题' }}</strong>
          </div>
          <dl>
            <div><dt>模式</dt><dd>{{ agentModeLabel(config.mode) }}</dd></div>
            <div><dt>模型</dt><dd>{{ config.model }}</dd></div>
          </dl>
        </header>

        <div class="agent-message-region">
          <div v-if="messageLoading" class="agent-message-state" role="status">正在加载消息</div>
          <PageState v-else-if="messageError && !messages.length" title="消息加载失败" :message="messageError" action="重试" @action="loadMessages" />
          <div v-else-if="!messages.length && !sending" class="agent-empty-state">
            <div><h2>从当前工地数据开始提问</h2><p>选择一个问题填入输入框，或直接输入你的问题。AI Agent 的能力取决于后端已配置的模式与模型。</p></div>
            <div class="agent-question-grid" aria-label="快捷问题">
              <button v-for="question in quickQuestions" :key="question" type="button" :disabled="!config.available" @click="useQuestion(question)">{{ question }}</button>
            </div>
          </div>
          <div v-else class="agent-message-list" aria-live="polite">
            <p v-if="messageError" class="agent-message-error" role="alert">{{ messageError }}</p>
            <article v-for="message in messages" :key="message.id || `${message.role}-${message.createdAt}`" class="agent-message" :class="`message-${message.role.toLowerCase()}`">
              <div class="agent-message-role"><strong>{{ message.role === 'ASSISTANT' ? 'AI Agent' : message.role === 'SYSTEM' ? '系统' : '你' }}</strong><span>{{ formatDate(message.createdAt) }}</span></div>
              <pre>{{ message.content }}</pre>
            </article>
            <article v-if="sending" class="agent-message agent-generating" role="status">
              <div class="agent-message-role"><strong>AI Agent</strong><span>等待后端响应</span></div>
              <div><span class="agent-generation-mark" aria-hidden="true"></span><strong>正在生成</strong><p>页面会在后端返回完整回答后一次性显示内容。</p></div>
            </article>
          </div>
        </div>

        <PaginationBar
          v-if="messageTotal > messagePageSize && !sending"
          v-model:page="messagePage"
          v-model:page-size="messagePageSize"
          :total="messageTotal"
          :page-sizes="[20, 50]"
          aria-label="AI 消息分页"
          @change="changeMessagePage"
        />

        <form class="agent-composer" @submit.prevent="send">
          <p v-if="composerError" class="agent-composer-error" role="alert">{{ composerError }}</p>
          <div v-if="!config.available && !configLoading" class="agent-config-note">
            <strong>AI 服务未启用</strong>
            <span>当前环境尚未配置可用的 AI 服务，请联系系统管理员完成配置后刷新页面。</span>
            <button v-if="configError" class="button button-secondary button-small" type="button" @click="loadConfig">重新读取配置</button>
          </div>
          <label>
            <span class="sr-only">输入问题</span>
            <textarea
              ref="composer"
              v-model="input"
              :rows="composerRows"
              :maxlength="config.maxContentChars"
              :disabled="!config.available || sending"
              placeholder="输入与当前工地安全相关的问题"
              @keydown="handleComposerKeydown"
            ></textarea>
          </label>
          <div class="agent-composer-actions">
            <span>Enter 发送 · Shift + Enter 换行</span>
            <span class="agent-character-count">{{ input.length }} / {{ config.maxContentChars }}</span>
            <button class="button button-primary" type="submit" :disabled="!canSend">{{ sending ? '正在生成' : '发送' }}</button>
          </div>
        </form>
      </section>
    </div>
  </section>
</template>
