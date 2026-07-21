<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { api, formatApiError, toQuery } from '../api'
import EChart from '../components/EChart.vue'
import PageState from '../components/PageState.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { formatDate, statusLabels, statusTone, typeLabels } from '../format'

const store = useStore()
const data = ref(null)
const loading = ref(true)
const error = ref('')
const riskSummary = computed(() => {
  const trend = data.value?.riskTrend || []
  if (!trend.length) return '近七日暂无风险事件记录。'
  return `近七日风险事件：${trend.map(item => `${item.date} ${Number(item.count) || 0} 条`).join('，')}。`
})

async function load() {
  loading.value = true; error.value = ''
  try { data.value = await api(`/dashboard${toQuery({ siteId: store.state.siteId })}`) }
  catch (reason) { error.value = formatApiError(reason) }
  finally { loading.value = false }
}

const riskOption = computed(() => ({
  color: ['#002fa7'],
  grid: { left: 44, right: 18, top: 24, bottom: 34, containLabel: true },
  tooltip: { trigger: 'axis' },
  xAxis: { type: 'category', data: data.value?.riskTrend.map(item => item.date) || [], axisLabel: { hideOverlap: true }, axisLine: { lineStyle: { color: '#a9acb3' } }, axisTick: { show: false } },
  yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#efeff1' } } },
  series: [{ type: 'line', data: data.value?.riskTrend.map(item => item.count) || [], showSymbol: false, lineStyle: { width: 2 }, areaStyle: { color: 'rgba(0,47,167,.08)' } }]
}))

const deviceOption = computed(() => ({
  color: ['#002fa7', '#a9acb3'],
  grid: { left: 16, right: 18, top: 44, bottom: 20, containLabel: true },
  legend: { top: 8, right: 12 },
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  xAxis: { type: 'value', splitLine: { lineStyle: { color: '#efeff1' } } },
  yAxis: { type: 'category', data: (data.value?.deviceTypes || []).map(item => typeLabels[item.type] || '未知设备类型'), axisTick: { show: false }, axisLine: { show: false } },
  series: [
    { name: '在线', type: 'bar', stack: 'devices', data: (data.value?.deviceTypes || []).map(item => item.onlineCount), barWidth: 12 },
    { name: '离线', type: 'bar', stack: 'devices', data: (data.value?.deviceTypes || []).map(item => item.count - item.onlineCount), barWidth: 12 }
  ]
}))

onMounted(load)
watch(() => store.state.siteId, load)
watch(() => store.state.latestEvent, event => { if (event?.type?.startsWith('alarm.') || event?.type === 'telemetry.updated') load() })
</script>

<template>
  <section>
    <header class="page-heading">
      <div><div class="folio"><svg viewBox="0 0 24 24"><use href="#app-icon-dashboard" /></svg></div><h1>综合首页</h1><p>从设备状态、风险趋势和未闭环告警观察当前工地安全态势。所有指标由服务端按工地范围聚合。</p></div>
      <div class="heading-meta"><div><span>统计时点</span><strong>{{ data?.summary.statisticsAt ? formatDate(data.summary.statisticsAt) : '—' }}</strong></div><div><span>数据性质</span><strong>{{ data?.summary.dataLabel || '—' }}</strong></div></div>
    </header>

    <div v-if="loading" class="loading-bar">正在加载工地聚合指标</div>
    <PageState v-else-if="error" title="首页数据加载失败" :message="error" action="重试" @action="load" />
    <template v-else-if="data">
      <div class="metric-grid">
        <article class="metric-card"><div class="metric-label"><span>设备总数</span><span class="metric-index">01</span></div><div class="metric-value">{{ data.summary.deviceCount }}<small>台</small></div><div class="metric-foot">已纳入平台管理的全部设备</div></article>
        <article class="metric-card"><div class="metric-label"><span>设备在线率</span><span class="metric-index">02</span></div><div class="metric-value">{{ data.summary.onlineRate }}<small>%</small></div><div class="metric-foot">{{ data.summary.onlineCount }} 台在线</div></article>
        <article class="metric-card"><div class="metric-label"><span>未闭环告警</span><span class="metric-index">03</span></div><div class="metric-value">{{ data.summary.openAlarmCount }}<small>条</small></div><div class="metric-foot">包含待确认、处理中和已解决</div></article>
        <article class="metric-card"><div class="metric-label"><span>待复核 AI 风险</span><span class="metric-index">04</span></div><div class="metric-value">{{ data.summary.pendingRiskCount }}<small>条</small></div><div class="metric-foot">需监管员确认有效或标记误报</div></article>
      </div>

      <div class="content-grid dashboard-grid">
        <section class="panel"><div class="panel-header"><div><h2>近七日风险事件</h2><p>{{ data.summary.dataLabel }}，按风险记录日期聚合，单位：条</p></div></div><EChart :option="riskOption" :summary="riskSummary" /></section>
        <section class="panel"><div class="panel-header"><div><h2>设备类型与在线状态</h2><p>按设备类型统计</p></div></div><EChart :option="deviceOption" summary="各类设备总量及在线数量的横向堆叠对比。" /></section>
      </div>

      <section class="panel dashboard-alarm-panel">
        <div class="panel-header"><div><h2>最新告警</h2><p>按最近发生时间排序</p></div><RouterLink class="text-link" to="/alarms">查看告警中心</RouterLink></div>
        <div class="data-table-wrap">
          <table class="data-table"><thead><tr><th>告警</th><th>区域</th><th>等级</th><th>状态</th><th>最近发生</th></tr></thead><tbody>
            <tr v-for="alarm in data.latestAlarms" :key="alarm.id"><td><span class="cell-primary">{{ alarm.title }}</span><span class="cell-secondary">{{ alarm.code }}</span></td><td>{{ alarm.zoneName }}</td><td><StatusBadge :label="alarm.severity === 'HIGH' ? '高' : alarm.severity === 'MEDIUM' ? '中' : '低'" :tone="statusTone(alarm.severity)" /></td><td><StatusBadge :label="statusLabels[alarm.status] || '未知状态'" :tone="statusTone(alarm.status)" /></td><td class="tabular">{{ formatDate(alarm.lastOccurredAt) }}</td></tr>
          </tbody></table><PageState v-if="!data.latestAlarms.length" title="暂无最新告警" message="当前工地没有未闭环告警记录。" />
        </div>
      </section>
    </template>
  </section>
</template>
