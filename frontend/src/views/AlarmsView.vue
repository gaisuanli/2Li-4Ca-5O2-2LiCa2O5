<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { api, apiBlob, formatApiError, jsonBody, toQuery } from '../api'
import PageState from '../components/PageState.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { formatDate, sourceTypeLabels, statusLabels, statusTone } from '../format'

const store = useStore()
const data = ref({ items: [], total: 0 })
const detail = ref(null)
const selectedId = ref(null)
const loading = ref(true)
const error = ref('')
const zones = ref([])
const exportError = ref('')
const exporting = ref(false)
const note = ref('')
const page = ref(1)
const pageSize = ref(10)
const filters = reactive({ keyword: '', status: '', severity: '', source: '', zoneId: '', from: '', to: '' })
const canHandle = computed(() => ['ADMIN', 'SUPERVISOR'].includes(store.state.user?.role))
const nextAction = computed(() => ({ PENDING: ['CONFIRM', '确认并开始处理'], PROCESSING: ['RESOLVE', '标记已解决'], RESOLVED: ['CLOSE', '关闭告警'] })[detail.value?.status])

async function load() {
  loading.value = true; error.value = ''
  try {
    data.value = await api(`/alarms${toQuery({ siteId: store.state.siteId, ...filters, page: page.value, pageSize: pageSize.value })}`)
    if (!data.value.items.some(item => item.id === selectedId.value)) selectedId.value = data.value.items[0]?.id || null
    await loadDetail()
  } catch (reason) { error.value = formatApiError(reason) }
  finally { loading.value = false }
}
async function loadZones() {
  try { zones.value = await api(`/sites/${store.state.siteId}/zones`) }
  catch (reason) { zones.value = []; exportError.value = `区域筛选加载失败：${formatApiError(reason)}` }
}
async function loadDetail() { detail.value = selectedId.value ? await api(`/alarms/${selectedId.value}`) : null }
async function select(id) { selectedId.value = id; await loadDetail() }
async function transition() {
  await api(`/alarms/${selectedId.value}/actions`, { method: 'POST', body: jsonBody({ action: nextAction.value[0], note: note.value }) })
  note.value = ''; await load(); store.dispatch('notify', { tone: 'info', message: '告警状态和处置记录已更新' })
}
function queryAlarms() { page.value = 1; load() }
async function exportAlarms() {
  exporting.value = true
  exportError.value = ''
  try {
    const result = await apiBlob(`/alarms/export${toQuery({ siteId: store.state.siteId, ...filters })}`)
    const url = URL.createObjectURL(result.blob)
    const link = document.createElement('a')
    link.href = url
    link.download = result.filename
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
    store.dispatch('notify', { tone: 'info', message: '告警查询结果已导出' })
  } catch (reason) {
    exportError.value = formatApiError(reason, '导出失败')
  } finally {
    exporting.value = false
  }
}
onMounted(() => { loadZones(); load() })
watch(() => store.state.siteId, () => { page.value = 1; filters.zoneId = ''; loadZones(); load() })
watch(() => store.state.latestEvent, event => { if (event?.type?.startsWith('alarm.')) load() })
</script>

<template>
  <section>
    <header class="page-heading"><div><div class="folio">09 / 14</div><h1>告警中心</h1><p>对设备规则、环境规则、AI 风险和系统故障告警进行筛选与单向状态流转。</p></div><div class="heading-meta"><div><span>查询结果</span><strong>{{ data.total }} 条</strong></div><div><span>状态顺序</span><strong>确认 → 处理 → 关闭</strong></div></div></header>
    <section class="panel">
      <div class="filter-bar alarm-filter"><label class="field"><span>关键字</span><input v-model="filters.keyword" placeholder="编号或标题" @keyup.enter="queryAlarms" /></label><label class="field"><span>状态</span><select v-model="filters.status"><option value="">全部状态</option><option v-for="value in ['PENDING','PROCESSING','RESOLVED','CLOSED']" :key="value" :value="value">{{ statusLabels[value] }}</option></select></label><label class="field"><span>等级</span><select v-model="filters.severity"><option value="">全部等级</option><option value="HIGH">高</option><option value="MEDIUM">中</option><option value="LOW">低</option></select></label><label class="field"><span>来源</span><select v-model="filters.source"><option value="">全部来源</option><option value="DEVICE_RULE">设备规则</option><option value="ENVIRONMENT_RULE">环境规则</option><option value="AI_RISK">AI 风险</option><option value="SYSTEM">系统故障</option></select></label><label class="field"><span>区域</span><select v-model="filters.zoneId"><option value="">全部区域</option><option v-for="zone in zones" :key="zone.id" :value="zone.id">{{ zone.name }}</option></select></label><label class="field"><span>开始时间</span><input v-model="filters.from" type="datetime-local" /></label><label class="field"><span>结束时间</span><input v-model="filters.to" type="datetime-local" /></label><button class="button button-primary" type="button" @click="queryAlarms">查询</button><button class="button button-secondary" type="button" :disabled="exporting" @click="exportAlarms">{{ exporting ? '正在导出' : '导出 CSV' }}</button></div>
      <p v-if="exportError" class="form-error" role="alert">{{ exportError }}</p>
      <div v-if="loading" class="loading-bar">正在加载告警数据</div>
      <PageState v-else-if="error" title="告警数据加载失败" :message="error" action="重试" @action="load" />
      <div v-else class="alarm-layout">
        <div class="data-table-wrap"><table class="data-table"><caption class="sr-only">告警查询结果</caption><thead><tr><th>告警</th><th>区域 / 设备</th><th>等级</th><th>状态</th><th>次数</th><th>最近发生</th></tr></thead><tbody><tr v-for="alarm in data.items" :key="alarm.id" :class="{ selected: alarm.id === selectedId }" :aria-selected="alarm.id === selectedId" tabindex="0" @click="select(alarm.id)" @keydown.enter="select(alarm.id)" @keydown.space.prevent="select(alarm.id)"><td><span class="cell-primary">{{ alarm.title }}</span><span class="cell-secondary">{{ alarm.code }} · {{ sourceTypeLabels[alarm.sourceType] || '未知来源' }}</span></td><td><span class="cell-primary">{{ alarm.zoneName }}</span><span class="cell-secondary">{{ alarm.deviceName || '无关联设备' }}</span></td><td><StatusBadge :label="alarm.severity === 'HIGH' ? '高' : alarm.severity === 'MEDIUM' ? '中' : '低'" :tone="statusTone(alarm.severity)" /></td><td><StatusBadge :label="statusLabels[alarm.status] || '未知状态'" :tone="statusTone(alarm.status)" /></td><td class="tabular">{{ alarm.occurrences }}</td><td class="tabular">{{ formatDate(alarm.lastOccurredAt) }}</td></tr></tbody></table><PageState v-if="!data.items.length" title="暂无告警" message="当前筛选条件下没有告警记录。" /><PaginationBar v-if="data.total" v-model:page="page" v-model:page-size="pageSize" :total="data.total" aria-label="告警列表分页" @change="load" /></div>
        <aside v-if="detail" class="alarm-detail"><div class="alarm-detail-head"><span>{{ detail.code }}</span><StatusBadge :label="statusLabels[detail.status]" :tone="statusTone(detail.status)" /></div><h2>{{ detail.title }}</h2><p>{{ detail.description }}</p><dl class="detail-list"><div><dt>区域</dt><dd>{{ detail.zoneName }}</dd></div><div><dt>设备</dt><dd>{{ detail.deviceName || '无关联设备' }}</dd></div><div><dt>重复次数</dt><dd>{{ detail.occurrences }}</dd></div><div><dt>首次发生</dt><dd>{{ formatDate(detail.firstOccurredAt) }}</dd></div></dl><div class="action-timeline"><article v-for="action in detail.actions" :key="action.id"><span>{{ formatDate(action.createdAt) }}</span><strong>{{ statusLabels[action.fromStatus] }} → {{ statusLabels[action.toStatus] }}</strong><p>{{ action.operatorName }} · {{ action.note || '未填写备注' }}</p></article><p v-if="!detail.actions.length" class="timeline-empty">暂无处置记录</p></div><form v-if="canHandle && nextAction" class="alarm-action-form" @submit.prevent="transition"><label class="field"><span>处置备注</span><textarea v-model.trim="note" required placeholder="记录现场处置情况"></textarea></label><button class="button button-primary" type="submit">{{ nextAction[1] }}</button></form></aside>
        <PageState v-else class="alarm-detail-empty" title="选择一条告警" message="从左侧列表选择告警，可查看详情和处置记录。" />
      </div>
    </section>
  </section>
</template>
