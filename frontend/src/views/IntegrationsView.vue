<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { api, formatApiError, jsonBody, toQuery } from '../api'
import PageState from '../components/PageState.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { formatDate, statusLabels, statusTone } from '../format'
import {
  imageBase64FromDataUrl,
  integrationFacts,
  integrationStateLabel,
  integrationStateTone
} from '../integrations'

const MAX_IMAGE_BYTES = 8 * 1024 * 1024
const ACCEPTED_IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp'])
const TYPE_NAMES = {
  VIDEO: '真实视频',
  VISION_AI: '视觉 AI',
  SPRINKLER_GATEWAY: '喷淋网关',
  PRODUCTION_MONITORING: '生产监控'
}
const TYPE_DESCRIPTIONS = {
  VIDEO: '展示视频地址配置和端点探测结果。传输端点可达不等于浏览器已完成解码与鉴权。',
  VISION_AI: '健康检查和单张图片推理均通过后端受控适配器执行；未启用时不会写入风险记录。',
  SPRINKLER_GATEWAY: '区分演示、停用和 HTTP 网关。演示模式不代表现场喷淋设备已经接入。',
  PRODUCTION_MONITORING: '检查数据库和指标注册表；健康与 Prometheus 端点由后端实施访问控制。'
}
const FACT_LABELS = {
  totalChannels: '摄像头总数',
  configuredChannels: '已配置地址',
  onlineConfiguredChannels: '在线且已配置',
  allowedHosts: '视频地址白名单',
  enabled: '适配器启用',
  baseUrl: '服务基地址',
  credentialConfigured: '访问凭据',
  mode: '运行模式',
  callbackConfigured: '回调认证',
  healthEndpoint: '健康检查端点',
  metricsEndpoint: '指标端点',
  metricsEndpointRequiresAdmin: '指标端点鉴权',
  probedChannels: '本次探测通道',
  reachableChannels: '可达通道',
  httpStatus: 'HTTP 状态',
  adapterMode: '适配器模式',
  modelFilePresent: '模型文件',
  notice: '服务说明',
  database: '数据库',
  registeredMeters: '已注册指标',
  cameraId: '摄像头 ID',
  cameraCode: '摄像头编号',
  protocol: '传输协议'
}

const store = useStore()
const integrations = ref([])
const integrationCheckedAt = ref(null)
const integrationLoading = ref(true)
const integrationError = ref('')
const checkResults = reactive({})
const checkErrors = reactive({})
const checking = reactive({})

const cameras = ref([])
const cameraTotal = ref(0)
const cameraPage = ref(1)
const cameraPageSize = ref(10)
const cameraKeyword = ref('')
const appliedCameraKeyword = ref('')
const cameraLoading = ref(true)
const cameraError = ref('')
const streamDrafts = reactive({})
const streamErrors = reactive({})
const streamSaving = reactive({})

const selectedCameraId = ref(null)
const visionFileInput = ref(null)
const selectedImageName = ref('')
const imageBase64 = ref('')
const imagePreview = ref('')
const imageError = ref('')
const inferenceLoading = ref(false)
const inferenceError = ref('')
const inferenceResult = ref(null)

let integrationRequestId = 0
let cameraRequestId = 0
let inferenceRequestId = 0

const currentSiteId = computed(() => Number(store.state.siteId))
const configuredCount = computed(() => integrations.value.filter(item =>
  !['NOT_CONFIGURED', 'DISABLED', 'MISCONFIGURED', 'DOWN'].includes(item.state)
).length)
const selectedCamera = computed(() => cameras.value.find(camera => Number(camera.id) === Number(selectedCameraId.value)) || null)
const canInfer = computed(() => Boolean(
  selectedCamera.value && imageBase64.value && !imageError.value && !cameraLoading.value && !inferenceLoading.value
))

function isCurrentSite(siteId) {
  return siteId === currentSiteId.value
}

function integrationName(item) {
  return item?.name || TYPE_NAMES[item?.type] || item?.type || '未知集成'
}

function factLabel(key) {
  return FACT_LABELS[key] || key
}

function factValue(key, value) {
  if (Array.isArray(value)) return value.length ? value.join('、') : '未设置'
  if (typeof value === 'boolean') {
    if (key === 'credentialConfigured' || key === 'callbackConfigured') return value ? '已配置' : '未配置'
    if (key === 'modelFilePresent') return value ? '存在' : '不存在'
    if (key === 'metricsEndpointRequiresAdmin') return value ? '需要管理员权限' : '未要求管理员权限'
    return value ? '是' : '否'
  }
  if (value && typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function factsOf(item) {
  return integrationFacts(item)
}

function resetRecord(record) {
  Object.keys(record).forEach(key => delete record[key])
}

async function loadIntegrations({ silent = false } = {}) {
  const requestId = ++integrationRequestId
  const siteId = currentSiteId.value
  if (!Number.isFinite(siteId) || siteId <= 0) {
    integrations.value = []
    integrationCheckedAt.value = null
    integrationError.value = '尚未选择可访问的工地。'
    integrationLoading.value = false
    return
  }
  if (!silent) integrationLoading.value = true
  integrationError.value = ''
  try {
    const data = await api(`/integrations${toQuery({ siteId })}`)
    if (requestId !== integrationRequestId || !isCurrentSite(siteId)) return
    integrations.value = Array.isArray(data?.items) ? data.items : []
    integrationCheckedAt.value = data?.checkedAt || null
  } catch (reason) {
    if (requestId !== integrationRequestId || !isCurrentSite(siteId)) return
    integrations.value = []
    integrationCheckedAt.value = null
    integrationError.value = formatApiError(reason, '集成状态加载失败')
  } finally {
    if (requestId === integrationRequestId && isCurrentSite(siteId)) integrationLoading.value = false
  }
}

async function checkIntegration(item) {
  const type = item.type
  const siteId = currentSiteId.value
  checking[type] = true
  delete checkErrors[type]
  try {
    const result = await api(`/integrations/${encodeURIComponent(type)}/check${toQuery({ siteId })}`, { method: 'POST' })
    if (!isCurrentSite(siteId)) return
    checkResults[type] = result
    store.dispatch('notify', {
      tone: integrationStateTone(result.state) === 'danger' ? 'danger' : 'info',
      message: `${integrationName(item)}检查完成：${integrationStateLabel(result.state)}`
    })
  } catch (reason) {
    if (isCurrentSite(siteId)) checkErrors[type] = formatApiError(reason, `${integrationName(item)}检查失败`)
  } finally {
    if (isCurrentSite(siteId)) checking[type] = false
  }
}

function syncStreamDrafts(items) {
  resetRecord(streamDrafts)
  items.forEach(camera => { streamDrafts[camera.id] = camera.streamUrl || '' })
}

async function loadCameras() {
  const requestId = ++cameraRequestId
  const siteId = currentSiteId.value
  if (!Number.isFinite(siteId) || siteId <= 0) {
    cameras.value = []
    cameraTotal.value = 0
    cameraError.value = '尚未选择可访问的工地。'
    cameraLoading.value = false
    return
  }
  cameraLoading.value = true
  cameraError.value = ''
  try {
    const data = await api(`/cameras${toQuery({
      siteId,
      keyword: appliedCameraKeyword.value,
      page: cameraPage.value,
      pageSize: cameraPageSize.value
    })}`)
    if (requestId !== cameraRequestId || !isCurrentSite(siteId)) return
    cameras.value = Array.isArray(data?.items) ? data.items : []
    cameraTotal.value = Number(data?.total) || 0
    syncStreamDrafts(cameras.value)
    if (!cameras.value.some(camera => Number(camera.id) === Number(selectedCameraId.value))) {
      selectedCameraId.value = cameras.value[0]?.id || null
      clearInferenceResult()
    }
  } catch (reason) {
    if (requestId !== cameraRequestId || !isCurrentSite(siteId)) return
    cameras.value = []
    cameraTotal.value = 0
    selectedCameraId.value = null
    cameraError.value = formatApiError(reason, '摄像头加载失败')
  } finally {
    if (requestId === cameraRequestId && isCurrentSite(siteId)) cameraLoading.value = false
  }
}

function queryCameras() {
  appliedCameraKeyword.value = cameraKeyword.value.trim()
  cameraPage.value = 1
  loadCameras()
}

async function saveStream(camera, clear = false) {
  const cameraId = camera.id
  const siteId = currentSiteId.value
  const streamUrl = clear ? '' : String(streamDrafts[cameraId] || '').trim()
  delete streamErrors[cameraId]
  if (!clear && !streamUrl) {
    streamErrors[cameraId] = '请输入视频流地址，或使用“清除地址”。'
    return
  }
  streamSaving[cameraId] = true
  try {
    const updated = await api(`/cameras/${cameraId}/stream`, {
      method: 'PUT',
      body: jsonBody({ siteId, streamUrl })
    })
    if (!isCurrentSite(siteId)) return
    cameras.value = cameras.value.map(item => Number(item.id) === Number(cameraId) ? { ...item, ...updated } : item)
    streamDrafts[cameraId] = updated.streamUrl || ''
    delete checkResults.VIDEO
    delete checkErrors.VIDEO
    await loadIntegrations({ silent: true })
    store.dispatch('notify', { tone: 'info', message: clear ? `${camera.name}的视频地址已清除` : `${camera.name}的视频地址已保存` })
  } catch (reason) {
    if (isCurrentSite(siteId)) streamErrors[cameraId] = formatApiError(reason, '视频地址保存失败')
  } finally {
    if (isCurrentSite(siteId)) streamSaving[cameraId] = false
  }
}

function readFileAsDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.addEventListener('load', () => resolve(String(reader.result || '')))
    reader.addEventListener('error', () => reject(new Error('图片读取失败，请重新选择。')))
    reader.readAsDataURL(file)
  })
}

async function selectImage(event) {
  const file = event.currentTarget.files?.[0]
  selectedImageName.value = ''
  imageBase64.value = ''
  imagePreview.value = ''
  imageError.value = ''
  clearInferenceResult()
  if (!file) return
  if (!ACCEPTED_IMAGE_TYPES.has(file.type)) {
    imageError.value = '仅支持 JPEG、PNG 或 WebP 图片。'
    event.currentTarget.value = ''
    return
  }
  if (file.size > MAX_IMAGE_BYTES) {
    imageError.value = '图片不能超过 8 MB。'
    event.currentTarget.value = ''
    return
  }
  try {
    const dataUrl = await readFileAsDataUrl(file)
    const encoded = imageBase64FromDataUrl(dataUrl)
    if (!encoded) throw new Error('图片编码为空，请重新选择。')
    selectedImageName.value = file.name
    imageBase64.value = encoded
    imagePreview.value = dataUrl
  } catch (reason) {
    imageError.value = reason.message || '图片读取失败，请重新选择。'
    event.currentTarget.value = ''
  }
}

function clearImage() {
  selectedImageName.value = ''
  imageBase64.value = ''
  imagePreview.value = ''
  imageError.value = ''
  if (visionFileInput.value) visionFileInput.value.value = ''
  clearInferenceResult()
}

function clearInferenceResult() {
  inferenceRequestId++
  inferenceLoading.value = false
  inferenceError.value = ''
  inferenceResult.value = null
}

async function runInference() {
  if (!canInfer.value) return
  const requestId = ++inferenceRequestId
  const siteId = currentSiteId.value
  const cameraId = Number(selectedCameraId.value)
  inferenceLoading.value = true
  inferenceError.value = ''
  inferenceResult.value = null
  try {
    const result = await api('/integrations/vision-ai/infer', {
      method: 'POST',
      body: jsonBody({ siteId, cameraId, imageBase64: imageBase64.value })
    })
    if (requestId !== inferenceRequestId || !isCurrentSite(siteId)) return
    inferenceResult.value = result
    const accepted = Number(result?.acceptedRiskCount) || 0
    store.dispatch('notify', {
      tone: 'info',
      message: accepted ? `已写入 ${accepted} 条待人工复核风险` : '推理完成，未写入待复核风险'
    })
  } catch (reason) {
    if (requestId === inferenceRequestId && isCurrentSite(siteId)) {
      inferenceError.value = formatApiError(reason, '视觉 AI 推理失败')
    }
  } finally {
    if (requestId === inferenceRequestId && isCurrentSite(siteId)) inferenceLoading.value = false
  }
}

function resetForSite() {
  integrationRequestId++
  cameraRequestId++
  inferenceRequestId++
  resetRecord(checkResults)
  resetRecord(checkErrors)
  resetRecord(checking)
  resetRecord(streamDrafts)
  resetRecord(streamErrors)
  resetRecord(streamSaving)
  integrations.value = []
  cameras.value = []
  cameraTotal.value = 0
  integrationCheckedAt.value = null
  cameraPage.value = 1
  cameraKeyword.value = ''
  appliedCameraKeyword.value = ''
  selectedCameraId.value = null
  clearImage()
  Promise.allSettled([loadIntegrations(), loadCameras()])
}

onMounted(() => Promise.allSettled([loadIntegrations(), loadCameras()]))
watch(() => store.state.siteId, resetForSite)
</script>

<template>
  <section class="integrations-page">
    <header class="page-heading">
      <div>
        <div class="folio"><svg viewBox="0 0 24 24"><use href="#app-icon-integrations" /></svg></div>
        <h1>集成中心</h1>
        <p>集中核对真实视频、视觉 AI、喷淋网关和生产监控。配置状态与主动检查结果分开呈现，未配置的外部系统不会标记为已接入。</p>
      </div>
      <div class="heading-meta">
        <div><span>已配置或可用</span><strong>{{ configuredCount }} / {{ integrations.length || 4 }} 项</strong></div>
        <div><span>状态读取时间</span><strong>{{ formatDate(integrationCheckedAt) }}</strong></div>
      </div>
    </header>

    <section class="panel" aria-labelledby="integration-matrix-title">
      <div class="panel-header">
        <div><h2 id="integration-matrix-title">接入验收矩阵</h2><p>“检查”只验证服务端能够核验的链路，不替代现场设备、画面质量和联动效果验收。</p></div>
        <button class="button button-secondary button-small" type="button" :disabled="integrationLoading" @click="loadIntegrations()">刷新配置状态</button>
      </div>
      <div v-if="integrationLoading" class="loading-bar" role="status">正在读取外部集成配置</div>
      <PageState v-else-if="integrationError" title="集成状态加载失败" :message="integrationError" action="重试" @action="loadIntegrations" />
      <PageState v-else-if="!integrations.length" title="暂无集成状态" message="服务端未返回当前工地的集成配置。" action="重新读取" @action="loadIntegrations" />
      <div v-else class="integration-matrix">
        <article v-for="(item, index) in integrations" :key="item.type" class="integration-card">
          <header class="integration-card-head">
            <span class="integration-index">{{ String(index + 1).padStart(2, '0') }}</span>
            <div class="integration-card-title"><h3>{{ integrationName(item) }}</h3><p>{{ TYPE_DESCRIPTIONS[item.type] || '外部系统配置与检查状态。' }}</p></div>
            <StatusBadge :label="integrationStateLabel(item.state)" :tone="integrationStateTone(item.state)" />
          </header>

          <dl v-if="factsOf(item).length" class="integration-facts">
            <div v-for="fact in factsOf(item)" :key="fact.key" class="integration-fact">
              <dt>{{ factLabel(fact.key) }}</dt><dd>{{ factValue(fact.key, fact.value) }}</dd>
            </div>
          </dl>

          <div class="integration-summary">
            <button class="button button-secondary button-small" type="button" :disabled="checking[item.type]" @click="checkIntegration(item)">
              {{ checking[item.type] ? '正在检查' : '主动检查' }}
            </button>
            <p v-if="checkErrors[item.type]" class="integration-check-error" role="alert">{{ checkErrors[item.type] }}</p>
          </div>

          <section v-if="checkResults[item.type]" class="integration-check-result" aria-live="polite">
            <div class="integration-check-head">
              <div><span>最近检查</span><strong>{{ formatDate(checkResults[item.type].checkedAt) }}</strong></div>
              <StatusBadge :label="integrationStateLabel(checkResults[item.type].state)" :tone="integrationStateTone(checkResults[item.type].state)" />
            </div>
            <p v-if="checkResults[item.type].message" class="integration-check-message">{{ checkResults[item.type].message }}</p>
            <dl v-if="factsOf(checkResults[item.type]).length" class="integration-facts">
              <div v-for="fact in factsOf(checkResults[item.type])" :key="fact.key" class="integration-fact">
                <dt>{{ factLabel(fact.key) }}</dt><dd>{{ factValue(fact.key, fact.value) }}</dd>
              </div>
            </dl>
            <div v-if="checkResults[item.type].checks?.length" class="integration-checks">
              <article v-for="(check, checkIndex) in checkResults[item.type].checks" :key="check.cameraId || checkIndex" class="integration-check">
                <div class="integration-check-meta">
                  <strong>{{ check.cameraCode || `检查项 ${checkIndex + 1}` }}</strong>
                  <StatusBadge :label="integrationStateLabel(check.state)" :tone="integrationStateTone(check.state)" />
                </div>
                <p v-if="check.message">{{ check.message }}</p>
                <dl v-if="factsOf(check).length" class="integration-facts">
                  <div v-for="fact in factsOf(check)" :key="fact.key" class="integration-fact">
                    <dt>{{ factLabel(fact.key) }}</dt><dd>{{ factValue(fact.key, fact.value) }}</dd>
                  </div>
                </dl>
              </article>
            </div>
          </section>
        </article>
      </div>
    </section>

    <div class="integration-workspace">
      <section class="panel integration-camera-panel" aria-labelledby="camera-stream-title">
        <div class="panel-header">
          <div><h2 id="camera-stream-title">摄像头真实地址</h2><p>保存或清除服务端视频流地址；仅允许后端白名单中的 HTTP(S)、RTSP 或 RTMP 主机。</p></div>
          <span>{{ cameraTotal }} 路</span>
        </div>
        <form class="filter-bar" role="search" @submit.prevent="queryCameras">
          <label class="field"><span>摄像头关键字</span><input v-model="cameraKeyword" type="search" placeholder="编号或名称" /></label>
          <button class="button button-secondary" type="submit">查询</button>
        </form>
        <div v-if="cameraLoading" class="loading-bar" role="status">正在加载摄像头分页数据</div>
        <PageState v-else-if="cameraError" title="摄像头加载失败" :message="cameraError" action="重试" @action="loadCameras" />
        <div v-else class="data-table-wrap">
          <table class="data-table">
            <caption class="sr-only">摄像头真实视频地址配置</caption>
            <thead><tr><th>摄像头</th><th>区域</th><th>通道状态</th><th>真实视频流地址</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="camera in cameras" :key="camera.id">
                <td><span class="cell-primary">{{ camera.name }}</span><span class="cell-secondary">{{ camera.code }}</span></td>
                <td>{{ camera.zoneName }}</td>
                <td><StatusBadge :label="statusLabels[camera.playbackStatus] || '未知状态'" :tone="statusTone(camera.playbackStatus)" /></td>
                <td>
                  <div class="stream-editor">
                    <label :for="`camera-stream-${camera.id}`" class="sr-only">{{ camera.name }}视频流地址</label>
                    <input :id="`camera-stream-${camera.id}`" v-model.trim="streamDrafts[camera.id]" type="text" inputmode="url" autocomplete="off" placeholder="填写白名单内的视频流地址" @keydown.enter.prevent="saveStream(camera)" />
                    <span v-if="camera.streamProtocol" class="cell-secondary">当前协议：{{ camera.streamProtocol }}</span>
                    <span v-if="streamErrors[camera.id]" class="camera-operation-error" role="alert">{{ streamErrors[camera.id] }}</span>
                  </div>
                </td>
                <td>
                  <div class="table-actions">
                    <button class="button button-primary button-small" type="button" :disabled="streamSaving[camera.id]" @click="saveStream(camera)">保存</button>
                    <button class="button button-secondary button-small" type="button" :disabled="streamSaving[camera.id] || !camera.streamUrl" @click="saveStream(camera, true)">清除地址</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
          <PageState v-if="!cameras.length" title="暂无摄像头" message="当前工地或筛选条件下没有摄像头记录。" />
          <PaginationBar v-if="cameraTotal" v-model:page="cameraPage" v-model:page-size="cameraPageSize" :total="cameraTotal" aria-label="摄像头配置分页" @change="loadCameras" />
        </div>
      </section>

      <section class="panel vision-infer-panel" aria-labelledby="vision-infer-title">
        <div class="panel-header"><div><h2 id="vision-infer-title">视觉 AI 单图验收</h2><p>本地图片以纯 Base64 提交给后端适配器；有效检测会写入 AI 风险待复核队列。</p></div></div>
        <div class="panel-body vision-workspace">
          <form @submit.prevent="runInference">
            <label class="field">
              <span>关联摄像头（当前分页）</span>
              <select v-model="selectedCameraId" required :disabled="cameraLoading" @change="clearInferenceResult">
                <option :value="null" disabled>请选择摄像头</option>
                <option v-for="camera in cameras" :key="camera.id" :value="camera.id">{{ camera.name }} · {{ camera.code }}</option>
              </select>
            </label>
            <label class="field">
              <span>现场图片</span>
              <input ref="visionFileInput" type="file" accept="image/jpeg,image/png,image/webp" @change="selectImage" />
            </label>
            <p class="integration-disclaimer">支持 JPEG、PNG、WebP，最大 8 MB。页面不会用演示检测替代真实适配器响应。</p>
            <p v-if="imageError" class="form-error" role="alert">{{ imageError }}</p>
            <p v-if="inferenceError" class="form-error" role="alert">{{ inferenceError }}</p>
            <div class="table-actions">
              <button class="button button-primary" type="submit" :disabled="!canInfer">{{ inferenceLoading ? '正在推理' : '提交真实推理' }}</button>
              <button class="button button-secondary" type="button" :disabled="!selectedImageName || inferenceLoading" @click="clearImage">清除图片</button>
            </div>
          </form>

          <div v-if="imagePreview" class="vision-file-preview">
            <img :src="imagePreview" :alt="`${selectedImageName}本地预览`" />
            <span>{{ selectedImageName }}</span>
          </div>
          <div v-else class="vision-file-empty"><strong>尚未选择图片</strong><span>选择图片后可在提交前核对画面。</span></div>

          <section v-if="inferenceResult" class="vision-result" aria-live="polite">
            <div><h3>推理返回结果</h3><StatusBadge :label="inferenceResult.reviewRequired ? '需要人工复核' : '无需人工复核'" :tone="inferenceResult.reviewRequired ? 'warning' : 'neutral'" /></div>
            <dl class="vision-result-grid">
              <div><dt>适配器模式</dt><dd>{{ inferenceResult.mode || '未返回' }}</dd></div>
              <div><dt>模型版本</dt><dd>{{ inferenceResult.modelVersion || '未返回' }}</dd></div>
              <div><dt>检测数量</dt><dd>{{ Number(inferenceResult.detectionCount) || 0 }}</dd></div>
              <div><dt>写入待复核</dt><dd>{{ Number(inferenceResult.acceptedRiskCount) || 0 }}</dd></div>
              <div><dt>风险记录 ID</dt><dd>{{ inferenceResult.riskIds?.length ? inferenceResult.riskIds.join('、') : '—' }}</dd></div>
            </dl>
            <p class="integration-disclaimer">{{ Number(inferenceResult.acceptedRiskCount) > 0 ? '检测结果已进入人工复核流程，尚未自动确认为真实风险或生成告警。' : '本次响应未写入待复核风险记录。' }}</p>
            <RouterLink v-if="Number(inferenceResult.acceptedRiskCount) > 0" class="button button-secondary button-small" to="/risks">进入 AI 风险复核</RouterLink>
          </section>
        </div>
      </section>
    </div>
  </section>
</template>
