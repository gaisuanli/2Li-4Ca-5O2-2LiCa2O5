<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { api, formatApiError, toQuery } from '../api'
import EChart from '../components/EChart.vue'
import PageState from '../components/PageState.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import TowerModel from '../components/TowerModel.vue'
import { formatDate, metricLabel, statusLabels, statusTone } from '../format'
import { collectAllPages } from '../pagination'
import { alignSeriesByTime } from '../series'

const store = useStore()
const devices = ref([])
const deviceTotal = ref(0)
const devicePage = ref(1)
const devicePageSize = ref(20)
const deviceId = ref(null)
const latest = ref([])
const wind = ref([])
const weight = ref([])
const rules = ref([])
const ruleError = ref('')
const historyMetric = ref('windSpeed')
const history = ref({ items: [], total: 0 })
const historyPage = ref(1)
const historyPageSize = ref(10)
const historyLoading = ref(false)
const historyError = ref('')
const loading = ref(true)
const error = ref('')
const device = computed(() => devices.value.find(item => item.id === deviceId.value))
const metrics = computed(() => Object.fromEntries(latest.value.map(item => [item.code, item])))
const alignedTrend = computed(() => alignSeriesByTime({ windSpeed: wind.value, weight: weight.value }))
const metricCodes = ['windSpeed', 'weight', 'amplitude', 'moment', 'rotation', 'height', 'obliquity']
const effectiveRules = computed(() => rules.value.filter(rule => {
  if (!rule.enabled) return false
  if (rule.scopeType === 'DEVICE') return Number(rule.scopeId) === Number(deviceId.value)
  if (rule.scopeType === 'SITE') return true
  return rule.scopeType === 'TYPE' && rule.targetDeviceType === 'TOWER_CRANE'
}))
const thresholds = computed(() => Object.fromEntries(effectiveRules.value.map(rule => [rule.metricCode, rule])))
const thresholdSummary = computed(() => {
  const parts = ['windSpeed', 'weight'].map(code => thresholds.value[code])
    .filter(Boolean)
    .map(rule => `${metricLabel(rule.metricCode)} ${rule.operator} ${Number(rule.thresholdValue)}`)
  return parts.length ? parts.join('；') : '当前账号无可显示的有效阈值，告警仍由服务端规则判断'
})
let loadingDeviceList = false
let ruleRequestId = 0

function clearTelemetry() {
  latest.value = []
  wind.value = []
  weight.value = []
}

async function loadDevices() {
  loadingDeviceList = true
  try {
    const data = await api(`/devices${toQuery({
      siteId: store.state.siteId,
      type: 'TOWER_CRANE',
      page: devicePage.value,
      pageSize: devicePageSize.value
    })}`)
    devices.value = data.items
    deviceTotal.value = data.total
    if (!devices.value.some(item => item.id === deviceId.value)) deviceId.value = devices.value[0]?.id || null
  } finally {
    loadingDeviceList = false
  }
}

async function loadTelemetry() {
  loading.value = true
  error.value = ''
  if (!deviceId.value) {
    clearTelemetry()
    loading.value = false
    return
  }
  try {
    const [latestData, windData, weightData] = await Promise.all([
      api(`/telemetry/latest${toQuery({ deviceId: deviceId.value })}`),
      api(`/telemetry/trend${toQuery({ deviceId: deviceId.value, metric: 'windSpeed', limit: 200 })}`),
      api(`/telemetry/trend${toQuery({ deviceId: deviceId.value, metric: 'weight', limit: 200 })}`)
    ])
    latest.value = latestData
    wind.value = windData
    weight.value = weightData
  } catch (reason) {
    error.value = formatApiError(reason)
  } finally {
    loading.value = false
  }
}

async function loadRules() {
  const requestId = ++ruleRequestId
  const requestedSiteId = store.state.siteId
  ruleError.value = ''
  if (!['ADMIN', 'SUPERVISOR'].includes(store.state.user?.role)) {
    rules.value = []
    return
  }
  try {
    const items = await collectAllPages((currentPage, currentPageSize) => api(`/rules${toQuery({
      siteId: requestedSiteId,
      enabled: true,
      page: currentPage,
      pageSize: currentPageSize
    })}`))
    if (requestId !== ruleRequestId || requestedSiteId !== store.state.siteId) return
    rules.value = items
  } catch (reason) {
    if (requestId !== ruleRequestId) return
    rules.value = []
    ruleError.value = formatApiError(reason)
  }
}

async function loadHistory() {
  historyError.value = ''
  if (!deviceId.value) {
    history.value = { items: [], total: 0 }
    return
  }
  historyLoading.value = true
  try {
    history.value = await api(`/telemetry/history${toQuery({
      deviceId: deviceId.value,
      metric: historyMetric.value,
      page: historyPage.value,
      pageSize: historyPageSize.value
    })}`)
  } catch (reason) {
    history.value = { items: [], total: 0 }
    historyError.value = formatApiError(reason)
  } finally {
    historyLoading.value = false
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    await Promise.all([loadDevices(), loadRules()])
    await Promise.all([loadTelemetry(), loadHistory()])
  } catch (reason) {
    error.value = formatApiError(reason)
    loading.value = false
  }
}

async function loadDevicePage() {
  await load()
}

const trendOption = computed(() => ({
  color: ['#002fa7', '#646870'],
  grid: { left: 20, right: 24, top: 44, bottom: 20, containLabel: true }, legend: { top: 8 }, tooltip: { trigger: 'axis' },
  xAxis: { type: 'category', data: alignedTrend.value.timestamps.map(formatDate), axisLabel: { hideOverlap: true, fontSize: 11 }, axisLine: { lineStyle: { color: '#a9acb3' } } },
  yAxis: [{ type: 'value', name: 'm/s', splitLine: { lineStyle: { color: '#efeff1' } } }, { type: 'value', name: 't', splitLine: { show: false } }],
  series: [
    { name: '风速', type: 'line', data: alignedTrend.value.values.windSpeed, connectNulls: false, showSymbol: false, markLine: thresholds.value.windSpeed ? { silent: true, data: [{ yAxis: Number(thresholds.value.windSpeed.thresholdValue), name: '风速阈值' }], lineStyle: { color: '#101114', type: 'dashed', width: 1 } } : undefined },
    { name: '吊重', type: 'line', yAxisIndex: 1, data: alignedTrend.value.values.weight, connectNulls: false, showSymbol: false, markLine: thresholds.value.weight ? { silent: true, data: [{ yAxis: Number(thresholds.value.weight.thresholdValue), name: '吊重阈值' }], lineStyle: { color: '#101114', type: 'dashed', width: 1 } } : undefined }
  ]
}))

onMounted(load)
watch(deviceId, () => {
  if (loadingDeviceList) return
  historyPage.value = 1
  loadTelemetry()
  loadHistory()
}, { flush: 'sync' })
watch(historyMetric, () => { historyPage.value = 1; loadHistory() })
watch(() => store.state.siteId, () => {
  devicePage.value = 1
  deviceId.value = null
  clearTelemetry()
  load()
})
watch(() => store.state.latestEvent, event => {
  if (event?.type === 'telemetry.updated' && event.payload.deviceId === deviceId.value) {
    loadTelemetry()
    loadHistory()
  }
})
</script>

<template>
  <section>
    <header class="page-heading"><div><div class="folio"><svg viewBox="0 0 24 24"><use href="#app-icon-tower" /></svg></div><h1>塔吊分析</h1><p>查看塔吊关键指标、阈值趋势和三维模型参数绑定。告警判断在服务端完成。</p></div><label class="field heading-select"><span>本页塔吊设备</span><select v-model="deviceId" :disabled="!devices.length"><option v-for="item in devices" :key="item.id" :value="item.id">{{ item.name }} · {{ item.code }}</option></select></label></header>
    <div v-if="loading" class="loading-bar">正在加载塔吊数据和三维模型</div>
    <PageState v-else-if="error" title="塔吊数据加载失败" :message="error" action="重试" @action="load" />
    <PageState v-else-if="!device" title="暂无塔吊设备" message="当前工地没有可查看的塔吊设备。" />
    <template v-else>
      <div v-if="deviceTotal > devicePageSize" class="tower-device-pagination"><PaginationBar v-model:page="devicePage" v-model:page-size="devicePageSize" :total="deviceTotal" aria-label="塔吊设备分页" @change="loadDevicePage" /></div>
      <div class="tower-top-grid">
        <section class="panel"><div class="panel-header"><div><h2>{{ device.name }}</h2><p>{{ device.code }} · {{ device.zoneName }}</p></div><StatusBadge :label="statusLabels[device.connectionStatus]" :tone="statusTone(device.connectionStatus)" /></div><TowerModel :rotation="metrics.rotation?.value == null ? 0 : Number(metrics.rotation.value)" :height="metrics.height?.value == null ? null : Number(metrics.height.value)" :amplitude="metrics.amplitude?.value == null ? null : Number(metrics.amplitude.value)" /></section>
        <section v-if="latest.length" class="tower-metrics">
          <article v-for="(code, index) in metricCodes" :key="code" class="parameter-card"><div><span>{{ metricLabel(code) }}</span><small>{{ String(index + 1).padStart(2, '0') }}</small></div><strong>{{ metrics[code]?.value ?? '—' }}<em>{{ metrics[code]?.unit }}</em></strong><p>采集于 {{ formatDate(metrics[code]?.collectedAt) }}</p></article>
        </section>
        <PageState v-else title="暂无实时遥测" message="该塔吊尚未上报可展示的指标，设备信息仍可正常查看。" />
      </div>
      <section class="panel tower-trend-panel"><div class="panel-header"><div><h2>运行指标趋势</h2><p>{{ thresholdSummary }}</p><p v-if="ruleError" class="inline-error">阈值加载失败：{{ ruleError }}</p></div><span class="data-tag">按采集时间对齐</span></div><PageState v-if="!alignedTrend.timestamps.length" title="暂无趋势数据" message="该塔吊还没有风速或吊重历史记录。" /><EChart v-else :option="trendOption" summary="当前塔吊最近采集点的风速和吊重趋势，缺失点按采集时间保留为空。" height="340px" /></section>

      <section class="panel tower-history-panel">
        <div class="panel-header"><div><h2>遥测历史</h2><p>按服务端分页读取，共 {{ history.total }} 条</p></div><label class="field compact-field"><span>历史指标</span><select v-model="historyMetric"><option v-for="code in metricCodes" :key="code" :value="code">{{ metricLabel(code) }}</option></select></label></div>
        <div v-if="historyLoading" class="loading-bar">正在加载遥测历史</div>
        <PageState v-else-if="historyError" title="历史数据加载失败" :message="historyError" action="重试" @action="loadHistory" />
        <div v-else class="data-table-wrap"><table class="data-table"><caption class="sr-only">塔吊遥测历史</caption><thead><tr><th>采集时间</th><th>指标</th><th>数值</th><th>报文编号</th></tr></thead><tbody><tr v-for="item in history.items" :key="`${item.messageId}-${item.collectedAt}`"><td class="tabular">{{ formatDate(item.collectedAt) }}</td><td>{{ metricLabel(historyMetric) }}</td><td class="tabular">{{ item.value }} {{ item.unit || '' }}</td><td>{{ item.messageId }}</td></tr></tbody></table><PageState v-if="!history.items.length" title="暂无历史数据" message="该塔吊尚未上报所选指标。" /><PaginationBar v-if="history.total" v-model:page="historyPage" v-model:page-size="historyPageSize" :total="history.total" aria-label="塔吊遥测历史分页" @change="loadHistory" /></div>
      </section>
    </template>
  </section>
</template>
