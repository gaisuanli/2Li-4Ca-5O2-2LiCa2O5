<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { api, formatApiError, jsonBody, toQuery } from '../../api'
import { formatDate, statusTone } from '../../format'
import PageState from '../PageState.vue'
import PaginationBar from '../PaginationBar.vue'
import StatusBadge from '../StatusBadge.vue'

const props = defineProps({ siteId: { type: [Number, String], required: true } })
const store = useStore()
const channels = ref([])
const runtime = ref(null)
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const loading = ref(true)
const error = ref('')
const operationError = ref('')
const submitting = ref(false)
const actionId = ref(null)
const showCreate = ref(false)
const form = reactive({ name: '', type: 'LOG', endpointUrl: '', credentialEnvName: '' })

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [data, runtimeData] = await Promise.all([
      api(`/push-channels${toQuery({ siteId: props.siteId, page: page.value, pageSize: pageSize.value })}`),
      api('/push-runtime')
    ])
    channels.value = data.items
    total.value = data.total
    runtime.value = runtimeData
  } catch (reason) {
    error.value = formatApiError(reason)
  } finally {
    loading.value = false
  }
}

function resetForm() {
  Object.assign(form, { name: '', type: 'LOG', endpointUrl: '', credentialEnvName: '' })
}

async function create() {
  submitting.value = true
  operationError.value = ''
  try {
    await api('/push-channels', {
      method: 'POST',
      body: jsonBody({
        siteId: Number(props.siteId),
        name: form.name,
        type: form.type,
        endpointUrl: form.type === 'WEBHOOK' ? form.endpointUrl : null,
        credentialEnvName: form.type === 'WEBHOOK' ? (form.credentialEnvName || null) : null
      })
    })
    store.dispatch('notify', { tone: 'info', message: '推送渠道已创建' })
    showCreate.value = false
    resetForm()
    page.value = 1
    await load()
  } catch (reason) {
    operationError.value = formatApiError(reason)
  } finally {
    submitting.value = false
  }
}

async function toggle(channel) {
  actionId.value = channel.id
  operationError.value = ''
  try {
    await api(`/push-channels/${channel.id}/enabled`, {
      method: 'PATCH',
      body: jsonBody({ enabled: !channel.enabled })
    })
    store.dispatch('notify', { tone: 'info', message: `${channel.name}已${channel.enabled ? '停用' : '启用'}` })
    await load()
  } catch (reason) {
    operationError.value = formatApiError(reason)
  } finally {
    actionId.value = null
  }
}

onMounted(load)
watch(() => props.siteId, () => {
  page.value = 1
  operationError.value = ''
  load()
})
</script>

<template>
  <section class="governance-workspace" aria-labelledby="channel-workspace-title">
    <div class="workspace-toolbar">
      <div>
        <h2 id="channel-workspace-title">推送渠道</h2>
        <p>LOG 渠道用于本地验收；Webhook 仅向服务端白名单中的地址发送已批准报告。</p>
      </div>
      <button class="button button-primary" type="button" @click="showCreate = !showCreate">{{ showCreate ? '取消新增' : '新建推送渠道' }}</button>
    </div>

    <div v-if="runtime" class="runtime-strip" aria-label="Webhook 运行时约束">
      <div><span>Webhook</span><strong>{{ runtime.webhookEnabled ? '服务端已启用' : '服务端未启用' }}</strong></div>
      <div><span>白名单地址</span><strong>{{ runtime.allowedEndpointCount }} 个</strong></div>
      <div><span>HTTP 回环</span><strong>{{ runtime.allowHttpLoopback ? '允许' : '禁止' }}</strong></div>
    </div>

    <form v-if="showCreate" class="panel governance-editor" @submit.prevent="create">
      <div class="panel-header"><div><h3>新建推送渠道</h3><p>凭据只填写服务端环境变量名称，浏览器不会接收或保存密钥值。</p></div></div>
      <div class="governance-form-grid">
        <label class="field"><span>渠道名称</span><input v-model.trim="form.name" maxlength="120" required /></label>
        <label class="field"><span>渠道类型</span><select v-model="form.type"><option value="LOG">LOG</option><option value="WEBHOOK">Webhook</option></select></label>
        <label v-if="form.type === 'WEBHOOK'" class="field governance-field-wide"><span>Webhook 地址</span><input v-model.trim="form.endpointUrl" type="url" maxlength="500" required /></label>
        <label v-if="form.type === 'WEBHOOK'" class="field governance-field-wide"><span>凭据环境变量名称</span><input v-model.trim="form.credentialEnvName" maxlength="100" pattern="[A-Z][A-Z0-9_]{1,99}" /></label>
      </div>
      <div class="governance-form-actions"><button class="button button-primary" type="submit" :disabled="submitting">{{ submitting ? '正在创建' : '创建渠道' }}</button></div>
    </form>

    <p v-if="operationError" class="form-error governance-operation-error" role="alert">{{ operationError }}</p>
    <div v-if="loading" class="loading-bar">正在加载推送渠道</div>
    <PageState v-else-if="error" title="推送渠道加载失败" :message="error" action="重试" @action="load" />
    <section v-else class="panel governance-list-panel">
      <div class="data-table-wrap">
        <table class="data-table governance-table">
          <caption class="sr-only">推送渠道列表</caption>
          <thead><tr><th>渠道</th><th>类型</th><th>服务端就绪</th><th>凭据</th><th>状态</th><th>更新时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="channel in channels" :key="channel.id">
              <td><span class="cell-primary">{{ channel.name }}</span><span class="cell-secondary">{{ channel.endpointUrl || '本地发送记录' }}</span></td>
              <td>{{ channel.type }}</td>
              <td><StatusBadge :label="channel.runtimeReady ? '就绪' : '未就绪'" :tone="channel.runtimeReady ? 'success' : 'warning'" /></td>
              <td>{{ channel.credentialConfigured ? '已满足' : '缺少环境变量' }}</td>
              <td><StatusBadge :label="channel.enabled ? '启用' : '停用'" :tone="statusTone(channel.enabled ? 'ONLINE' : 'OFFLINE')" /></td>
              <td class="tabular">{{ formatDate(channel.updatedAt) }}</td>
              <td><button class="button button-secondary button-small" type="button" :disabled="actionId === channel.id" @click="toggle(channel)">{{ channel.enabled ? '停用' : '启用' }}</button></td>
            </tr>
          </tbody>
        </table>
        <PageState v-if="!channels.length" title="暂无推送渠道" message="当前工地尚未创建推送渠道。" />
        <PaginationBar v-if="total" v-model:page="page" v-model:page-size="pageSize" :total="total" aria-label="推送渠道分页" @change="load" />
      </div>
    </section>
  </section>
</template>
