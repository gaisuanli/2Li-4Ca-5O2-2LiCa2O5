<script setup>
import { computed, reactive, ref, watch } from 'vue'
import {
  agentProviderRoute,
  agentProviderStatus,
  createAgentProviderForm,
  normalizeAgentProviderConfig,
  prepareAgentProviderPayload
} from '../../agent-provider'

const props = defineProps({
  config: { type: Object, required: true },
  loading: Boolean,
  busy: Boolean,
  error: { type: String, default: '' },
  operationError: { type: String, default: '' },
  notice: { type: String, default: '' }
})

const emit = defineEmits(['refresh', 'save', 'clear'])
const expanded = ref(false)
const validationError = ref('')
const form = reactive(createAgentProviderForm())
let initialized = false

const normalizedConfig = computed(() => normalizeAgentProviderConfig(props.config))
const status = computed(() => props.error
  ? { code: 'ERROR', label: '读取失败' }
  : agentProviderStatus(normalizedConfig.value))
const route = computed(() => props.error
  ? { active: false, label: '状态未知' }
  : agentProviderRoute(normalizedConfig.value))
const formDisabled = computed(() => props.loading
  || props.busy
  || Boolean(props.error)
  || !normalizedConfig.value.userConfigEnabled
  || !normalizedConfig.value.credentialStorageAvailable)
const canClear = computed(() => normalizedConfig.value.apiKeyConfigured
  || Boolean(normalizedConfig.value.baseUrl)
  || Boolean(normalizedConfig.value.model))

watch(normalizedConfig, value => {
  Object.assign(form, createAgentProviderForm(value))
  validationError.value = ''
}, { immediate: true })

watch(() => props.loading, loading => {
  if (loading || initialized) return
  expanded.value = !normalizedConfig.value.apiKeyConfigured
  initialized = true
}, { immediate: true })

function toggleExpanded() {
  expanded.value = !expanded.value
  validationError.value = ''
  form.apiKey = ''
}

function submit() {
  const result = prepareAgentProviderPayload(form, normalizedConfig.value)
  validationError.value = result.error
  if (result.error) return
  emit('save', result.payload)
  form.apiKey = ''
}

function clearConfiguration() {
  if (!window.confirm('清除后，该账号将不再使用此第三方模型配置。是否继续？')) return
  validationError.value = ''
  form.apiKey = ''
  emit('clear')
}
</script>

<template>
  <section class="agent-provider-panel" aria-labelledby="agent-provider-title">
    <header class="agent-provider-head">
      <div class="agent-provider-title">
        <span>01</span>
        <div>
          <h2 id="agent-provider-title">模型供应商配置</h2>
          <p>为当前账号配置 OpenAI 兼容 API。密钥只允许覆盖写入，页面和接口都不会返回原值。</p>
        </div>
      </div>
      <div class="agent-provider-head-actions">
        <span class="status-badge" :class="`provider-status-${status.code.toLowerCase().replaceAll('_', '-')}`">{{ status.label }}</span>
        <button class="button button-secondary button-small" type="button" :aria-expanded="expanded" @click="toggleExpanded">
          {{ expanded ? '收起配置' : '打开配置' }}
        </button>
      </div>
    </header>

    <div class="agent-provider-track" aria-label="模型配置状态">
      <div :class="{ active: !error && normalizedConfig.userConfigEnabled }">
        <span>用户配置</span>
        <strong>{{ error ? '状态未知' : normalizedConfig.userConfigEnabled ? '允许' : '已停用' }}</strong>
      </div>
      <div :class="{ active: !error && normalizedConfig.credentialStorageAvailable }">
        <span>密钥存储</span>
        <strong>{{ error ? '状态未知' : normalizedConfig.credentialStorageAvailable ? '可用' : '不可用' }}</strong>
      </div>
      <div :class="{ active: !error && normalizedConfig.apiKeyConfigured }">
        <span>API Key</span>
        <strong>{{ error ? '状态未知' : normalizedConfig.apiKeyConfigured ? '已保存' : '未保存' }}</strong>
      </div>
      <div :class="{ active: route.active }">
        <span>问答路由</span>
        <strong>{{ route.label }}</strong>
      </div>
    </div>

    <div v-if="loading" class="agent-provider-state" role="status">正在读取当前账号的模型配置</div>
    <div v-else-if="error && !expanded" class="agent-provider-state agent-provider-state-error" role="alert">
      <span>{{ error }}</span>
      <button class="button button-secondary button-small" type="button" @click="$emit('refresh')">重试</button>
    </div>

    <form v-if="expanded && !loading" class="agent-provider-form" autocomplete="off" @submit.prevent="submit">
      <div class="agent-provider-fields">
        <label class="field">
          <span>Base URL</span>
          <input
            v-model.trim="form.baseUrl"
            type="url"
            list="agent-provider-base-urls"
            placeholder="https://api.example.com/v1"
            spellcheck="false"
            :disabled="formDisabled"
          />
          <datalist id="agent-provider-base-urls">
            <option v-for="baseUrl in normalizedConfig.approvedBaseUrls" :key="baseUrl" :value="baseUrl"></option>
          </datalist>
          <small>{{ normalizedConfig.approvedBaseUrls.length ? `可从后端批准的 ${normalizedConfig.approvedBaseUrls.length} 个地址中选择` : '提交后仍由服务器执行地址安全校验' }}</small>
        </label>

        <label class="field">
          <span>模型</span>
          <input
            v-model.trim="form.model"
            type="text"
            list="agent-provider-models"
            placeholder="输入或选择模型"
            spellcheck="false"
            :disabled="formDisabled"
          />
          <datalist id="agent-provider-models">
            <option v-for="model in normalizedConfig.approvedModels" :key="model" :value="model"></option>
          </datalist>
          <small>{{ normalizedConfig.approvedModels.length ? `后端批准了 ${normalizedConfig.approvedModels.length} 个模型` : '当前没有后端提供的模型候选项' }}</small>
        </label>

        <label class="field agent-provider-secret-field">
          <span>API Key</span>
          <input
            v-model="form.apiKey"
            type="password"
            name="agent-provider-api-key"
            autocomplete="new-password"
            placeholder="输入新密钥"
            spellcheck="false"
            :disabled="formDisabled"
          />
          <small>{{ normalizedConfig.apiKeyConfigured ? '已保存密钥；留空将保留，输入内容将覆盖' : '首次配置必须输入；保存后不会回显' }}</small>
        </label>
      </div>

      <div class="agent-provider-boundary">
        <strong>第三方数据边界</strong>
        <p>保存后，该账号发送的问答内容及受控知识库上下文会传输至所选第三方服务。请确认服务条款与数据处理要求后再启用。</p>
      </div>

      <p v-if="error" class="agent-provider-feedback is-error" role="alert">{{ error }}</p>
      <p v-if="validationError || operationError" class="agent-provider-feedback is-error" role="alert">{{ validationError || operationError }}</p>
      <p v-else-if="notice" class="agent-provider-feedback" role="status">{{ notice }}</p>

      <footer class="agent-provider-actions">
        <button class="button button-danger" type="button" :disabled="props.busy || !canClear" @click="clearConfiguration">清除当前配置</button>
        <div>
          <button class="button button-secondary" type="button" :disabled="props.busy" @click="$emit('refresh')">重新读取</button>
          <button class="button button-primary" type="submit" :disabled="formDisabled">{{ props.busy ? '正在保存' : '保存配置' }}</button>
        </div>
      </footer>
    </form>
  </section>
</template>
