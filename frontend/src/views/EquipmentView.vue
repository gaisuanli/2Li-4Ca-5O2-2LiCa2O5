<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { api, formatApiError, toQuery } from '../api'
import EChart from '../components/EChart.vue'
import PageState from '../components/PageState.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import {
  equipmentMetricUsesUnit,
  equipmentTypes,
  formatEquipmentMetricValue,
  metricsForEquipmentType,
  resolveEquipmentMetric
} from '../equipment'
import { formatDate, metricLabel, statusLabels, statusTone, typeLabels } from '../format'

const store = useStore()
const deviceType = ref('ELEVATOR')
const devices = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const deviceId = ref(null)
const latest = ref([])
const metricCode = ref('load')
const trend = ref([])
const listLoading = ref(true)
const listError = ref('')
const dataLoading = ref(false)
const dataError = ref('')
const trendLoading = ref(false)
const trendError = ref('')
let listRequestId = 0
let dataRequestId = 0
let trendRequestId = 0

const selectedDevice = computed(() => devices.value.find(item => item.id === deviceId.value))
const configuredMetrics = computed(() => metricsForEquipmentType(deviceType.value))
const latestByCode = computed(() => Object.fromEntries(latest.value.map(item => [item.code, item])))
const latestTimestamp = computed(() => latest.value.reduce((latestValue, item) => {
  if (!item.collectedAt) return latestValue
  if (!latestValue || new Date(item.collectedAt) > new Date(latestValue)) return item.collectedAt
  return latestValue
}, null))

function normalizeItems(payload) {
  if (Array.isArray(payload)) return payload
  return Array.isArray(payload?.items) ? payload.items : []
}

function clearTelemetry() {
  dataRequestId += 1
  trendRequestId += 1
  latest.value = []
  trend.value = []
  dataError.value = ''
  trendError.value = ''
  dataLoading.value = false
  trendLoading.value = false
}

async function loadDevices() {
  const requestId = ++listRequestId
  const requestedSiteId = store.state.siteId
  const requestedType = deviceType.value
  listLoading.value = true
  listError.value = ''
  clearTelemetry()
  try {
    const result = await api(`/devices${toQuery({
      siteId: requestedSiteId,
      type: requestedType,
      page: page.value,
      pageSize: pageSize.value
    })}`)
    if (requestId !== listRequestId || requestedSiteId !== store.state.siteId || requestedType !== deviceType.value) return
    devices.value = normalizeItems(result)
    total.value = Number(result?.total ?? devices.value.length)
    if (!devices.value.some(item => item.id === deviceId.value)) deviceId.value = devices.value[0]?.id || null
    if (deviceId.value) loadDeviceData()
  } catch (reason) {
    if (requestId !== listRequestId) return
    devices.value = []
    total.value = 0
    deviceId.value = null
    listError.value = formatApiError(reason)
  } finally {
    if (requestId === listRequestId) listLoading.value = false
  }
}

function selectType(type) {
  if (deviceType.value === type) return
  deviceType.value = type
  page.value = 1
  deviceId.value = null
  metricCode.value = metricsForEquipmentType(type)[0] || ''
  loadDevices()
}

function selectDevice(id) {
  if (deviceId.value === id) return
  deviceId.value = id
  loadDeviceData()
}

async function loadDeviceData() {
  const requestedDeviceId = deviceId.value
  if (!requestedDeviceId) {
    clearTelemetry()
    return
  }
  const requestId = ++dataRequestId
  dataLoading.value = true
  dataError.value = ''
  latest.value = []
  trend.value = []
  try {
    const result = await api(`/telemetry/latest${toQuery({ deviceId: requestedDeviceId })}`)
    if (requestId !== dataRequestId || requestedDeviceId !== deviceId.value) return
    latest.value = normalizeItems(result)
    metricCode.value = resolveEquipmentMetric(
      deviceType.value,
      latest.value.map(item => item.code),
      metricCode.value
    )
    if (latest.value.length && metricCode.value) loadTrend()
  } catch (reason) {
    if (requestId !== dataRequestId) return
    dataError.value = formatApiError(reason)
  } finally {
    if (requestId === dataRequestId) dataLoading.value = false
  }
}

async function loadTrend() {
  const requestedDeviceId = deviceId.value
  const requestedMetric = metricCode.value
  if (!requestedDeviceId || !requestedMetric) {
    trend.value = []
    return
  }
  const requestId = ++trendRequestId
  trendLoading.value = true
  trendError.value = ''
  trend.value = []
  try {
    const result = await api(`/telemetry/trend${toQuery({
      deviceId: requestedDeviceId,
      metric: requestedMetric,
      limit: 48
    })}`)
    if (requestId !== trendRequestId || requestedDeviceId !== deviceId.value || requestedMetric !== metricCode.value) return
    trend.value = normalizeItems(result)
  } catch (reason) {
    if (requestId !== trendRequestId) return
    trendError.value = formatApiError(reason)
  } finally {
    if (requestId === trendRequestId) trendLoading.value = false
  }
}

const trendOption = computed(() => ({
  color: ['#002fa7'],
  grid: { left: 20, right: 22, top: 26, bottom: 20, containLabel: true },
  tooltip: { trigger: 'axis' },
  xAxis: {
    type: 'category',
    data: trend.value.map(item => formatDate(item.collectedAt)),
    axisLabel: { hideOverlap: true, fontSize: 11 },
    axisLine: { lineStyle: { color: '#a9acb3' } },
    axisTick: { show: false }
  },
  yAxis: {
    type: 'value',
    name: trend.value[0]?.unit || '',
    splitLine: { lineStyle: { color: '#efeff1' } }
  },
  series: [{
    name: metricLabel(metricCode.value),
    type: 'line',
    data: trend.value.map(item => Number(item.value)),
    showSymbol: false,
    lineStyle: { width: 2 },
    areaStyle: { color: 'rgba(0,47,167,.08)' }
  }]
}))

onMounted(loadDevices)
watch(() => store.state.siteId, () => {
  page.value = 1
  deviceId.value = null
  loadDevices()
})
watch(() => store.state.latestEvent, event => {
  if (event?.type === 'telemetry.updated' && Number(event.payload?.deviceId) === Number(deviceId.value)) loadDeviceData()
})
</script>

<template>
  <section>
    <header class="page-heading">
      <div>
        <div class="folio"><svg viewBox="0 0 24 24"><use href="#app-icon-equipment" /></svg></div>
        <h1>大型设备监测</h1>
        <p>查看施工升降机、高支模和深基坑设备的已上报指标。离线设备保留最近一次持久化数据，但不将其标记为实时值。</p>
      </div>
      <div class="heading-meta">
        <div><span>设备类型</span><strong>{{ typeLabels[deviceType] }}</strong></div>
        <div><span>设备数量</span><strong>{{ total }} 台</strong></div>
      </div>
    </header>

    <div class="equipment-type-switch" aria-label="大型设备类型">
      <button
        v-for="(type, index) in equipmentTypes"
        :key="type"
        type="button"
        :class="{ active: deviceType === type }"
        :aria-pressed="deviceType === type"
        @click="selectType(type)"
      >
        <span>{{ typeLabels[type] }}</span>
        <small>{{ String(index + 1).padStart(2, '0') }} / {{ String(equipmentTypes.length).padStart(2, '0') }}</small>
      </button>
    </div>

    <div class="equipment-workspace">
      <section class="panel equipment-device-panel">
        <div class="panel-header"><div><h2>设备列表</h2><p>当前类型，按设备编号排序</p></div></div>
        <div v-if="listLoading" class="loading-bar">正在加载设备</div>
        <PageState v-else-if="listError" title="设备列表加载失败" :message="listError" action="重试" @action="loadDevices" />
        <template v-else>
          <div class="equipment-device-list">
            <button
              v-for="device in devices"
              :key="device.id"
              type="button"
              :class="{ selected: device.id === deviceId }"
              :aria-pressed="device.id === deviceId"
              @click="selectDevice(device.id)"
            >
              <span><strong>{{ device.name }}</strong><small>{{ device.code }} · {{ device.zoneName }}</small></span>
              <StatusBadge :label="statusLabels[device.connectionStatus]" :tone="statusTone(device.connectionStatus)" />
            </button>
          </div>
          <PageState v-if="!devices.length" title="暂无此类设备" :message="`当前工地没有${typeLabels[deviceType]}设备。`" />
          <PaginationBar
            v-if="total > pageSize"
            v-model:page="page"
            v-model:page-size="pageSize"
            :total="total"
            aria-label="大型设备列表分页"
            @change="loadDevices"
          />
        </template>
      </section>

      <div class="equipment-analysis">
        <PageState v-if="!selectedDevice && !listLoading && !listError" title="尚未选择设备" message="从左侧设备列表选择一项查看监测指标。" />
        <template v-else-if="selectedDevice">
          <section class="panel equipment-summary-panel">
            <div class="panel-header">
              <div><h2>{{ selectedDevice.name }}</h2><p>{{ selectedDevice.code }} · {{ selectedDevice.zoneName }} · {{ selectedDevice.location || '未填写安装位置' }}</p></div>
              <StatusBadge :label="statusLabels[selectedDevice.connectionStatus]" :tone="statusTone(selectedDevice.connectionStatus)" />
            </div>
            <div v-if="selectedDevice.connectionStatus === 'OFFLINE'" class="equipment-offline-note">
              设备当前离线。以下内容是数据库中的最近一次上报，不代表当前实时状态。
            </div>
            <div v-if="dataLoading" class="loading-bar">正在加载设备遥测</div>
            <PageState v-else-if="dataError" title="设备遥测加载失败" :message="dataError" action="重试" @action="loadDeviceData" />
            <PageState v-else-if="!latest.length" title="暂无遥测数据" message="设备已经登记，但尚未保存任何可展示的监测指标。" />
            <template v-else>
              <div class="equipment-data-time"><span>最近采集时间</span><strong>{{ formatDate(latestTimestamp) }}</strong></div>
              <div class="equipment-metric-grid">
                <article v-for="(code, index) in configuredMetrics" :key="code">
                  <div><span>{{ metricLabel(code) }}</span><small>{{ String(index + 1).padStart(2, '0') }}</small></div>
                  <strong>
                    {{ formatEquipmentMetricValue(code, latestByCode[code]?.value) }}
                    <em v-if="equipmentMetricUsesUnit(code) && latestByCode[code]?.unit">{{ latestByCode[code].unit }}</em>
                  </strong>
                  <p>{{ latestByCode[code] ? formatDate(latestByCode[code].collectedAt) : '该指标尚未上报' }}</p>
                </article>
              </div>
            </template>
          </section>

          <section class="panel equipment-trend-panel">
            <div class="panel-header">
              <div><h2>最近趋势</h2><p>最多读取最近 48 个已保存采集点</p></div>
              <label class="field compact-field">
                <span>指标</span>
                <select v-model="metricCode" @change="loadTrend">
                  <option v-for="code in configuredMetrics" :key="code" :value="code">{{ metricLabel(code) }}</option>
                </select>
              </label>
            </div>
            <div v-if="trendLoading" class="loading-bar">正在加载趋势数据</div>
            <PageState v-else-if="trendError" title="趋势数据加载失败" :message="trendError" action="重试" @action="loadTrend" />
            <PageState v-else-if="!trend.length" title="暂无趋势数据" :message="`${metricLabel(metricCode)}尚无历史采集点。`" />
            <EChart v-else :option="trendOption" :summary="`${selectedDevice.name}${metricLabel(metricCode)}最近 ${trend.length} 个采集点趋势。`" height="340px" />
          </section>
        </template>
      </div>
    </div>
  </section>
</template>
