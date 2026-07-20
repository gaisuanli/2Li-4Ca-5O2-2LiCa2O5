<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { api, formatApiError, jsonBody, toQuery } from '../api'
import EChart from '../components/EChart.vue'
import PageState from '../components/PageState.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { formatDate, metricLabel, statusLabels, statusTone } from '../format'
import { buildSprinklerAck } from '../sprinkler'

const store = useStore()
const summary = ref(null)
const tasks = ref([])
const taskTotal = ref(0)
const taskPage = ref(1)
const taskPageSize = ref(10)
const taskStatus = ref('')
const appliedTaskStatus = ref('')
const selectedStationId = ref(null)
const trendMetric = ref('pm25')
const trend = ref([])
const loading = ref(true)
const error = ref('')
const trendLoading = ref(false)
const trendError = ref('')
const taskLoading = ref(true)
const taskError = ref('')
const taskOperationError = ref('')
const taskActionId = ref(null)
const failureAckTaskId = ref(null)
const failureReason = ref('')
const taskForm = reactive({ reason: '现场道路扬尘，需要人工喷淋降尘' })
const station = computed(() => summary.value?.stations.find(item => item.id === selectedStationId.value))
const stationMetrics = computed(() => Object.fromEntries((station.value?.metrics || []).map(item => [item.code, item])))
const canControl = computed(() => ['ADMIN', 'SUPERVISOR', 'DEVICE_MANAGER'].includes(store.state.user?.role))
let trendRequestId = 0

async function loadTrend() {
  const deviceId = selectedStationId.value
  const metric = trendMetric.value
  const requestId = ++trendRequestId
  trendError.value = ''
  if (!deviceId) {
    trend.value = []
    trendLoading.value = false
    return
  }
  trendLoading.value = true
  try {
    const data = await api(`/environment/trend${toQuery({ deviceId, metric })}`)
    if (requestId === trendRequestId) trend.value = data
  } catch (reason) {
    if (requestId === trendRequestId) {
      trend.value = []
      trendError.value = formatApiError(reason)
    }
  } finally {
    if (requestId === trendRequestId) trendLoading.value = false
  }
}

async function loadSummary() {
  loading.value = true
  error.value = ''
  try {
    const data = await api(`/environment/summary${toQuery({ siteId: store.state.siteId })}`)
    summary.value = data
    if (!data.stations.some(item => item.id === selectedStationId.value)) selectedStationId.value = data.stations[0]?.id || null
    await loadTrend()
  } catch (reason) {
    error.value = formatApiError(reason)
  } finally {
    loading.value = false
  }
}

async function loadTasks() {
  taskLoading.value = true
  taskError.value = ''
  try {
    const data = await api(`/sprinkler-tasks${toQuery({
      siteId: store.state.siteId,
      status: appliedTaskStatus.value,
      page: taskPage.value,
      pageSize: taskPageSize.value
    })}`)
    tasks.value = data.items
    taskTotal.value = data.total
  } catch (reason) {
    taskError.value = formatApiError(reason)
  } finally {
    taskLoading.value = false
  }
}

const trendOption = computed(() => ({
  color: ['#002fa7'], grid: { left: 20, right: 22, top: 22, bottom: 20, containLabel: true }, tooltip: { trigger: 'axis' },
  xAxis: { type: 'category', data: trend.value.map(item => formatDate(item.collectedAt)), axisLabel: { hideOverlap: true, fontSize: 11 }, axisLine: { lineStyle: { color: '#a9acb3' } } },
  yAxis: { type: 'value', splitLine: { lineStyle: { color: '#efeff1' } } },
  series: [{ type: 'line', data: trend.value.map(item => item.value), showSymbol: false, areaStyle: { color: 'rgba(0,47,167,.08)' }, markLine: trendMetric.value === 'pm25' ? { silent: true, data: [{ yAxis: 75, name: '阈值' }], lineStyle: { color: '#101114', type: 'dashed', width: 1 } } : undefined }]
}))

async function runTaskAction(id, action, successMessage) {
  taskOperationError.value = ''
  taskActionId.value = id
  try {
    await action()
    await loadTasks()
    store.dispatch('notify', { tone: 'info', message: successMessage })
  } catch (reason) {
    taskOperationError.value = formatApiError(reason)
  } finally {
    taskActionId.value = null
  }
}

async function createTask() {
  await runTaskAction('CREATE', () => api('/sprinkler-tasks', {
    method: 'POST',
    body: jsonBody({ siteId: store.state.siteId, zoneId: station.value.zoneId, reason: taskForm.reason })
  }), '喷淋任务已创建，尚未下发到设备')
}

async function dispatchTask(id) {
  await runTaskAction(id, () => api(`/sprinkler-tasks/${id}/dispatch`, { method: 'POST' }), '喷淋任务已下发')
}

async function ackTask(id, success) {
  let payload
  try {
    payload = buildSprinklerAck(success, failureReason.value)
  } catch (reason) {
    taskOperationError.value = reason.message
    return
  }
  await runTaskAction(id, () => api(`/sprinkler-tasks/${id}/ack`, { method: 'POST', body: jsonBody(payload) }), success ? '已记录设备演示成功回执' : '已记录设备演示失败回执')
  if (!taskOperationError.value) closeFailureAck()
}

function openFailureAck(id) {
  failureAckTaskId.value = id
  failureReason.value = ''
  taskOperationError.value = ''
}

function closeFailureAck() {
  failureAckTaskId.value = null
  failureReason.value = ''
}

function queryTasks() {
  appliedTaskStatus.value = taskStatus.value
  taskPage.value = 1
  loadTasks()
}

onMounted(() => {
  loadSummary()
  loadTasks()
})
watch([selectedStationId, trendMetric], loadTrend)
watch(() => store.state.siteId, () => {
  trendRequestId += 1
  selectedStationId.value = null
  trend.value = []
  taskPage.value = 1
  loadSummary()
  loadTasks()
})
watch(() => store.state.latestEvent, event => {
  if (event?.type === 'sprinkler.task.changed' && Number(event.payload?.siteId) === Number(store.state.siteId)) loadTasks()
})
</script>

<template>
  <section>
    <header class="page-heading"><div><div class="folio">05 / 14</div><h1>环境分析</h1><p>对温湿度、颗粒物和噪声进行监测，并通过带设备校验和回执的任务控制喷淋。</p></div><div class="heading-meta"><div><span>统计时点</span><strong>{{ formatDate(summary?.statisticsAt) }}</strong></div><div><span>数据性质</span><strong>{{ summary?.dataLabel || '—' }}</strong></div></div></header>
    <div v-if="loading" class="loading-bar">正在加载环境监测站</div>
    <PageState v-else-if="error" title="环境数据加载失败" :message="error" action="重试" @action="loadSummary" />
    <PageState v-else-if="!summary?.stations.length" title="暂无环境监测站" message="当前工地没有已配置的环境监测设备。" />
    <template v-else>
      <div class="environment-toolbar"><label class="field"><span>环境监测站</span><select v-model="selectedStationId"><option v-for="item in summary.stations" :key="item.id" :value="item.id">{{ item.name }}</option></select></label><StatusBadge :label="statusLabels[station.connectionStatus]" :tone="statusTone(station.connectionStatus)" /></div>
      <div class="environment-metrics">
        <article v-for="code in ['pm25','pm10','noise','temperature','humidity']" :key="code" class="environment-card"><span>{{ metricLabel(code) }}</span><strong>{{ stationMetrics[code]?.value ?? '—' }}<small>{{ stationMetrics[code]?.unit }}</small></strong><p>{{ formatDate(stationMetrics[code]?.collectedAt) }}</p></article>
      </div>
      <div class="content-grid environment-grid">
        <section class="panel">
          <div class="panel-header"><div><h2>指标趋势</h2><p>{{ station.name }}</p></div><label class="field compact-field"><span>指标</span><select v-model="trendMetric"><option value="pm25">PM2.5</option><option value="pm10">PM10</option><option value="noise">噪声</option><option value="temperature">温度</option><option value="humidity">湿度</option></select></label></div>
          <div v-if="trendLoading" class="loading-bar">正在加载指标趋势</div>
          <PageState v-else-if="trendError" title="趋势加载失败" :message="trendError" action="重试" @action="loadTrend" />
          <PageState v-else-if="!trend.length" title="暂无趋势数据" message="当前监测站尚未上报该指标。" />
          <EChart v-else :option="trendOption" :summary="`${station.name}${metricLabel(trendMetric)}的历史趋势。`" height="330px" />
        </section>

        <section class="panel">
          <div class="panel-header"><div><h2>喷淋任务</h2><p>共 {{ taskTotal }} 条；页面回执仅用于业务闭环演示，不代表真实 PLC 通信</p></div></div>
          <div class="filter-bar"><label class="field"><span>任务状态</span><select v-model="taskStatus"><option value="">全部状态</option><option v-for="value in ['CREATED','DISPATCHED','EXECUTED','FAILED']" :key="value" :value="value">{{ statusLabels[value] }}</option></select></label><button class="button button-secondary" type="button" @click="queryTasks">查询</button></div>
          <div class="panel-body sprinkler-panel">
            <form v-if="canControl" @submit.prevent="createTask"><label class="field"><span>人工触发原因</span><textarea v-model.trim="taskForm.reason" required></textarea></label><button class="button button-primary" type="submit" :disabled="taskActionId !== null">{{ taskActionId === 'CREATE' ? '正在创建' : '创建任务' }}</button></form>
            <p v-if="taskOperationError" class="form-error" role="alert">{{ taskOperationError }}</p>
            <div v-if="taskLoading" class="loading-bar">正在加载喷淋任务</div>
            <PageState v-else-if="taskError" title="喷淋任务加载失败" :message="taskError" action="重试" @action="loadTasks" />
            <PageState v-else-if="!tasks.length" title="暂无喷淋任务" message="当前筛选条件下没有任务记录。" />
            <div v-else class="task-list"><article v-for="task in tasks" :key="task.id"><div><strong>{{ task.code }}</strong><span>{{ task.zoneName }} · 计划 {{ formatDate(task.plannedAt) }}<template v-if="task.commandId"> · {{ task.commandId }}</template></span></div><StatusBadge :label="statusLabels[task.status] || '未知状态'" :tone="statusTone(task.status)" /><p>{{ task.reason }}<template v-if="task.failureReason"> · 失败原因：{{ task.failureReason }}</template><template v-if="task.endedAt"> · 结束 {{ formatDate(task.endedAt) }}</template></p><div v-if="canControl" class="task-actions"><button v-if="task.status === 'CREATED'" class="button button-secondary button-small" type="button" :disabled="taskActionId === task.id" @click="dispatchTask(task.id)">生成并下发演示指令</button><template v-if="task.status === 'DISPATCHED'"><button class="button button-secondary button-small" type="button" :disabled="taskActionId === task.id" @click="ackTask(task.id, true)">记录演示成功回执</button><button class="button button-secondary button-small" type="button" :disabled="taskActionId === task.id" @click="openFailureAck(task.id)">记录演示失败回执</button></template></div><form v-if="failureAckTaskId === task.id" class="task-failure-form" @submit.prevent="ackTask(task.id, false)"><label class="field"><span>失败原因</span><textarea v-model.trim="failureReason" required maxlength="500" placeholder="例如：设备网关无响应"></textarea></label><div class="table-actions"><button class="button button-danger button-small" type="submit" :disabled="taskActionId === task.id">确认失败回执</button><button class="button button-secondary button-small" type="button" @click="closeFailureAck">取消</button></div></form></article></div>
          </div>
          <PaginationBar v-if="taskTotal" v-model:page="taskPage" v-model:page-size="taskPageSize" :total="taskTotal" aria-label="喷淋任务分页" @change="loadTasks" />
        </section>
      </div>

      <section class="panel environment-exceedance-panel">
        <div class="panel-header"><div><h2>环境超标记录</h2><p>最近返回的环境规则告警</p></div><RouterLink class="text-link" to="/alarms">前往告警中心</RouterLink></div>
        <div class="data-table-wrap"><table class="data-table"><caption class="sr-only">环境超标记录</caption><thead><tr><th>告警</th><th>区域</th><th>等级</th><th>状态</th><th>最近发生</th></tr></thead><tbody><tr v-for="alarm in summary.exceedances" :key="alarm.id"><td><span class="cell-primary">{{ alarm.title }}</span></td><td>{{ alarm.zoneName }}</td><td><StatusBadge :label="alarm.severity === 'HIGH' ? '高' : alarm.severity === 'MEDIUM' ? '中' : '低'" :tone="statusTone(alarm.severity)" /></td><td><StatusBadge :label="statusLabels[alarm.status] || '未知状态'" :tone="statusTone(alarm.status)" /></td><td class="tabular">{{ formatDate(alarm.occurredAt) }}</td></tr></tbody></table><PageState v-if="!summary.exceedances?.length" title="暂无环境超标" message="当前工地没有环境规则告警。" /></div>
      </section>
    </template>
  </section>
</template>
