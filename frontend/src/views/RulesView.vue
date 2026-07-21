<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { api, formatApiError, jsonBody, toQuery } from '../api'
import PageState from '../components/PageState.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { metricLabel, sourceTypeLabels, statusTone, typeLabels } from '../format'
import { collectAllPages } from '../pagination'
import {
  isSharedRuleMetric,
  normalizedRuleScope,
  RULE_METRIC_PROFILES,
  ruleDeviceTypesForMetric,
  sourceTypeForRuleMetric
} from '../rule-metrics'

const SHARED_DEVICE_METRICS = Object.freeze(['axialForce', 'settlement', 'xAngle', 'yAngle'])
const METRIC_GROUPS = Object.freeze([
  { label: '塔吊', codes: RULE_METRIC_PROFILES.TOWER_CRANE },
  { label: '环境监测', codes: RULE_METRIC_PROFILES.ENVIRONMENT },
  { label: '施工升降机', codes: RULE_METRIC_PROFILES.ELEVATOR },
  { label: '高支模', codes: RULE_METRIC_PROFILES.FORMWORK.filter(code => !SHARED_DEVICE_METRICS.includes(code)) },
  { label: '深基坑', codes: RULE_METRIC_PROFILES.FOUNDATION_PIT.filter(code => !SHARED_DEVICE_METRICS.includes(code)) },
  { label: '高支模与深基坑共享', codes: SHARED_DEVICE_METRICS }
])
const store = useStore()
const rules = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const loading = ref(true)
const error = ref('')
const operationError = ref('')
const actionRuleId = ref(null)
const submitting = ref(false)
const showCreate = ref(false)
const scopeDevices = ref([])
const scopeDeviceError = ref('')
const filters = reactive({ keyword: '', sourceType: '', enabled: '', metricCode: '' })
const appliedFilters = reactive({ keyword: '', sourceType: '', enabled: '', metricCode: '' })
const form = reactive({ name: '', metricCode: 'rotation', operator: '>', thresholdValue: null, severity: 'MEDIUM', scopeType: 'TYPE', scopeId: null, suppressionSeconds: 300 })
const selectedMetricDeviceTypes = computed(() => ruleDeviceTypesForMetric(form.metricCode))
const isSharedDeviceMetric = computed(() => isSharedRuleMetric(form.metricCode))
const eligibleDevices = computed(() => scopeDevices.value.filter(device => selectedMetricDeviceTypes.value.includes(device.type)))
const scopeOptions = computed(() => isSharedDeviceMetric.value
  ? [{ value: 'DEVICE', label: '指定设备' }]
  : [
      { value: 'SITE', label: '当前工地' },
      { value: 'TYPE', label: '当前工地同类设备' },
      { value: 'DEVICE', label: '指定设备' }
    ])
const scopeDescription = computed(() => {
  const targetNames = selectedMetricDeviceTypes.value.map(type => typeLabels[type] || type).join('、')
  if (isSharedDeviceMetric.value) {
    return `${displayMetricLabel(form.metricCode)}同时适用于${targetNames}，为避免设备类型推断歧义，只能绑定一台明确设备。`
  }
  if (form.scopeType === 'TYPE') return `类型规则将绑定当前工地的${targetNames || '匹配设备'}。`
  if (form.scopeType === 'DEVICE') return `设备列表仅显示支持${displayMetricLabel(form.metricCode)}的${targetNames || '匹配设备'}。`
  return '工地范围规则将按当前工地保存并由服务端执行。'
})
let scopeDeviceRequestId = 0

function sourceTypeForMetric(metricCode) {
  return sourceTypeForRuleMetric(metricCode)
}

function displayMetricLabel(metricCode) {
  return ({ amplitude: '幅度', moment: '力矩', obliquity: '倾斜度' })[metricCode] || metricLabel(metricCode)
}

function scopeLabel(rule) {
  const siteName = store.state.sites.find(site => Number(site.id) === Number(rule.scopeId))?.name || '当前工地'
  if (rule.scopeType === 'DEVICE') {
    const target = scopeDevices.value.find(device => Number(device.id) === Number(rule.scopeId))
    return target ? `指定设备 · ${target.name} (${target.code})` : `指定设备 · #${rule.scopeId}`
  }
  if (rule.scopeType === 'TYPE') return `${typeLabels[rule.targetDeviceType] || '未知设备类型'} · ${siteName}`
  return siteName
}

function ruleRequest(rule) {
  return {
    name: rule.name,
    sourceType: rule.sourceType,
    metricCode: rule.metricCode,
    operator: rule.operator,
    thresholdValue: rule.thresholdValue,
    severity: rule.severity,
    scopeType: rule.scopeType,
    scopeId: rule.scopeId,
    suppressionSeconds: rule.suppressionSeconds
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api(`/rules${toQuery({
      siteId: store.state.siteId,
      ...appliedFilters,
      page: page.value,
      pageSize: pageSize.value
    })}`)
    rules.value = data.items
    total.value = data.total
  } catch (reason) {
    error.value = formatApiError(reason)
  } finally {
    loading.value = false
  }
}

async function loadScopeDevices() {
  const requestId = ++scopeDeviceRequestId
  const requestedSiteId = store.state.siteId
  scopeDeviceError.value = ''
  try {
    const items = await collectAllPages((currentPage, currentPageSize) => api(`/devices${toQuery({
      siteId: requestedSiteId,
      page: currentPage,
      pageSize: currentPageSize
    })}`))
    if (requestId !== scopeDeviceRequestId || requestedSiteId !== store.state.siteId) return
    scopeDevices.value = items
    resolveFormScope()
  } catch (reason) {
    if (requestId !== scopeDeviceRequestId) return
    scopeDevices.value = []
    scopeDeviceError.value = formatApiError(reason)
  }
}

function resolveFormScope() {
  form.scopeType = normalizedRuleScope(form.metricCode, form.scopeType)
  if (!scopeOptions.value.some(option => option.value === form.scopeType)) {
    form.scopeType = scopeOptions.value[0].value
  }
  if (form.scopeType !== 'DEVICE') {
    form.scopeId = Number(store.state.siteId)
    return
  }
  if (!eligibleDevices.value.some(device => Number(device.id) === Number(form.scopeId))) {
    form.scopeId = eligibleDevices.value[0]?.id || null
  }
}

function queryRules() {
  Object.assign(appliedFilters, filters)
  page.value = 1
  load()
}

async function runRuleAction(rule, action, successMessage) {
  operationError.value = ''
  actionRuleId.value = rule.id
  try {
    await action()
    await load()
    store.dispatch('notify', { tone: 'info', message: successMessage })
  } catch (reason) {
    operationError.value = formatApiError(reason)
  } finally {
    actionRuleId.value = null
  }
}

async function toggle(rule) {
  await runRuleAction(
    rule,
    () => api(`/rules/${rule.id}/enabled`, { method: 'PATCH', body: jsonBody({ enabled: !rule.enabled }) }),
    `${rule.name}已${rule.enabled ? '停用' : '启用'}`
  )
}

async function save(rule) {
  await runRuleAction(
    rule,
    () => api(`/rules/${rule.id}`, { method: 'PUT', body: jsonBody(ruleRequest(rule)) }),
    `${rule.name}已保存`
  )
}

async function create() {
  submitting.value = true
  operationError.value = ''
  try {
    resolveFormScope()
    if (form.scopeType === 'DEVICE' && !form.scopeId) {
      operationError.value = '当前工地没有与所选指标匹配的设备，无法创建设备范围规则。'
      return
    }
    await api('/rules', {
      method: 'POST',
      body: jsonBody({
        ...form,
        sourceType: sourceTypeForMetric(form.metricCode),
        scopeId: Number(form.scopeId)
      })
    })
    showCreate.value = false
    page.value = 1
    await load()
    store.dispatch('notify', { tone: 'info', message: '规则已创建并绑定当前工地' })
  } catch (reason) {
    operationError.value = formatApiError(reason)
  } finally {
    submitting.value = false
  }
}

onMounted(() => { load(); loadScopeDevices() })
watch([() => form.metricCode, () => form.scopeType], resolveFormScope)
watch(() => store.state.siteId, () => {
  page.value = 1
  operationError.value = ''
  form.scopeId = Number(store.state.siteId)
  load()
  loadScopeDevices()
})
</script>

<template>
  <section>
    <header class="page-heading"><div><div class="folio"><svg viewBox="0 0 24 24"><use href="#app-icon-rules" /></svg></div><h1>规则配置</h1><p>阈值、适用范围和抑制窗口由服务端保存并执行。类型规则明确绑定当前工地，停用不会删除历史告警。</p></div><button class="button button-primary" type="button" @click="showCreate = !showCreate">{{ showCreate ? '取消新增' : '新增规则' }}</button></header>

    <form v-if="showCreate" class="panel rule-form" @submit.prevent="create">
      <div class="panel-header"><div><h2>新增阈值规则</h2><p>指标目录与服务端设备能力保持一致；共享指标必须明确绑定设备</p></div></div>
      <div class="inline-form">
        <label class="field"><span>规则名称</span><input v-model.trim="form.name" required /></label>
        <label class="field"><span>指标</span><select v-model="form.metricCode"><optgroup v-for="group in METRIC_GROUPS" :key="group.label" :label="group.label"><option v-for="code in group.codes" :key="code" :value="code">{{ displayMetricLabel(code) }}</option></optgroup></select></label>
        <label class="field"><span>运算符</span><select v-model="form.operator"><option v-for="op in ['>','>=','<','<=','=']" :key="op">{{ op }}</option></select></label>
        <label class="field"><span>阈值</span><input v-model.number="form.thresholdValue" type="number" step="0.1" required /></label>
        <label class="field"><span>等级</span><select v-model="form.severity"><option value="HIGH">高</option><option value="MEDIUM">中</option><option value="LOW">低</option></select></label>
        <label class="field"><span>作用范围</span><select v-model="form.scopeType"><option v-for="option in scopeOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>
        <label v-if="form.scopeType === 'DEVICE'" class="field"><span>目标设备</span><select v-model="form.scopeId" required><option v-if="!eligibleDevices.length" value="" disabled>当前工地无匹配设备</option><option v-for="device in eligibleDevices" :key="device.id" :value="device.id">{{ device.name }} · {{ device.code }}</option></select></label>
        <label class="field"><span>抑制秒数</span><input v-model.number="form.suppressionSeconds" type="number" min="30" required /></label>
        <button class="button button-primary" type="submit" :disabled="submitting || (form.scopeType === 'DEVICE' && !form.scopeId)">{{ submitting ? '正在保存' : '保存规则' }}</button>
        <p class="rule-scope-note" :class="{ 'is-restricted': isSharedDeviceMetric }">{{ scopeDescription }}</p>
      </div>
      <p v-if="scopeDeviceError" class="form-error" role="alert">设备范围加载失败：{{ scopeDeviceError }}</p>
    </form>

    <section class="panel">
      <form class="filter-bar" role="search" @submit.prevent="queryRules">
        <label class="field"><span>关键字</span><input v-model.trim="filters.keyword" type="search" placeholder="规则名称" /></label>
        <label class="field"><span>来源</span><select v-model="filters.sourceType"><option value="">全部来源</option><option value="DEVICE_RULE">设备规则</option><option value="ENVIRONMENT_RULE">环境规则</option></select></label>
        <label class="field"><span>指标</span><select v-model="filters.metricCode"><option value="">全部指标</option><optgroup v-for="group in METRIC_GROUPS" :key="group.label" :label="group.label"><option v-for="code in group.codes" :key="code" :value="code">{{ displayMetricLabel(code) }}</option></optgroup></select></label>
        <label class="field"><span>状态</span><select v-model="filters.enabled"><option value="">全部状态</option><option :value="true">启用</option><option :value="false">停用</option></select></label>
        <button class="button button-secondary" type="submit">查询</button>
      </form>
      <p v-if="operationError" class="form-error user-operation-error" role="alert">{{ operationError }}</p>
      <div v-if="loading" class="loading-bar">正在加载告警规则</div>
      <PageState v-else-if="error" title="规则数据加载失败" :message="error" action="重试" @action="load" />
      <div v-else class="data-table-wrap">
        <table class="data-table rule-table"><caption class="sr-only">告警规则列表</caption><thead><tr><th>规则</th><th>条件</th><th>等级</th><th>范围</th><th>抑制时间</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="rule in rules" :key="rule.id"><td><span class="cell-primary">{{ rule.name }}</span><span class="cell-secondary">{{ sourceTypeLabels[rule.sourceType] || '未知来源' }}</span></td><td><div class="rule-condition"><span>{{ displayMetricLabel(rule.metricCode) }}</span><select v-model="rule.operator"><option v-for="op in ['>','>=','<','<=','=']" :key="op">{{ op }}</option></select><input v-model.number="rule.thresholdValue" type="number" step="0.1" /></div></td><td><select v-model="rule.severity"><option value="HIGH">高</option><option value="MEDIUM">中</option><option value="LOW">低</option></select></td><td>{{ scopeLabel(rule) }}</td><td><input v-model.number="rule.suppressionSeconds" class="small-number" type="number" min="30" /> 秒</td><td><StatusBadge :label="rule.enabled ? '启用' : '停用'" :tone="statusTone(rule.enabled ? 'ONLINE' : 'OFFLINE')" /></td><td><div class="table-actions"><button class="button button-secondary button-small" type="button" :disabled="actionRuleId === rule.id" @click="save(rule)">保存</button><button class="button button-secondary button-small" type="button" :disabled="actionRuleId === rule.id" @click="toggle(rule)">{{ rule.enabled ? '停用' : '启用' }}</button></div></td></tr></tbody></table>
        <PageState v-if="!rules.length" title="暂无规则" message="当前筛选条件下没有告警规则。" />
        <PaginationBar v-if="total" v-model:page="page" v-model:page-size="pageSize" :total="total" aria-label="规则列表分页" @change="load" />
      </div>
    </section>
  </section>
</template>
