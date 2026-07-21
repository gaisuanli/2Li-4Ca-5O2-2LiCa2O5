<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { api, formatApiError, jsonBody, toQuery } from '../api'
import ChannelWorkspace from '../components/governance/ChannelWorkspace.vue'
import KnowledgeWorkspace from '../components/governance/KnowledgeWorkspace.vue'
import PageState from '../components/PageState.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { formatDate, statusTone } from '../format'
import {
  channelReadyForDelivery,
  governanceStatusLabel,
  governanceStatusTone,
  reportActions,
  REPORT_STATUSES,
  reportTemplateFields
} from '../governance'
import { collectAllPages } from '../pagination'

const store = useStore()
const activeModule = ref('knowledge')
const isAdmin = computed(() => store.state.user?.role === 'ADMIN')
const hasSite = computed(() => Number.isFinite(Number(store.state.siteId)) && Number(store.state.siteId) > 0)

const modules = [
  { id: 'knowledge', index: '01', label: '知识文档', summary: '受控发布与归档' },
  { id: 'templates', index: '02', label: '报告模板', summary: '字段约束与版本维护' },
  { id: 'reports', index: '03', label: '安全报告', summary: '生成、审核与投递' },
  { id: 'channels', index: '04', label: '推送渠道', summary: 'LOG 与 Webhook' }
]

const templateFields = reportTemplateFields()
const templateFieldTokens = templateFields.map(field => `{{${field}}}`)

const templates = ref([])
const templateTotal = ref(0)
const templatePage = ref(1)
const templatePageSize = ref(10)
const templateLoading = ref(false)
const templateError = ref('')
const templateOperationError = ref('')
const templateSaving = ref(false)
const templateActionId = ref(null)
const showTemplateEditor = ref(false)
const editingTemplateId = ref(null)
const templateEnabledFilter = ref('')
const templateForm = reactive({ name: '', description: '', bodyTemplate: '' })

const reports = ref([])
const reportTotal = ref(0)
const reportPage = ref(1)
const reportPageSize = ref(10)
const reportLoading = ref(false)
const reportError = ref('')
const reportOperationError = ref('')
const reportActionId = ref(null)
const reportStatusFilter = ref('')
const reportTemplates = ref([])
const reportChannels = ref([])
const choicesLoading = ref(false)
const choicesError = ref('')
const showGenerate = ref(false)
const generating = ref(false)
const generateForm = reactive({ templateId: '', title: '' })
const editingReportId = ref(null)
const reportSaving = ref(false)
const reportForm = reactive({ title: '', content: '' })
const reviewTarget = ref(null)
const reviewNote = ref('')
const deliveryTarget = ref(null)
const selectedChannelId = ref('')
const delivering = ref(false)
const deliveries = ref([])
const deliveryTotal = ref(0)
const deliveryPage = ref(1)
const deliveryPageSize = ref(10)
const deliveryLoading = ref(false)
const deliveryError = ref('')

const enabledChannels = computed(() => reportChannels.value.filter(channel => channel.enabled))
const readyChannels = computed(() => enabledChannels.value.filter(channelReadyForDelivery))

function notify(message, tone = 'info') {
  store.dispatch('notify', { tone, message })
}

function currentSiteId() {
  return Number(store.state.siteId)
}

async function loadTemplates() {
  if (!hasSite.value) return
  templateLoading.value = true
  templateError.value = ''
  try {
    const data = await api(`/report-templates${toQuery({
      siteId: currentSiteId(),
      enabled: templateEnabledFilter.value === '' ? undefined : templateEnabledFilter.value,
      page: templatePage.value,
      pageSize: templatePageSize.value
    })}`)
    templates.value = data.items
    templateTotal.value = data.total
  } catch (reason) {
    templateError.value = formatApiError(reason)
  } finally {
    templateLoading.value = false
  }
}

function resetTemplateEditor() {
  editingTemplateId.value = null
  Object.assign(templateForm, { name: '', description: '', bodyTemplate: '' })
}

function startCreateTemplate() {
  resetTemplateEditor()
  showTemplateEditor.value = true
}

function startEditTemplate(template) {
  editingTemplateId.value = template.id
  Object.assign(templateForm, {
    name: template.name,
    description: template.description || '',
    bodyTemplate: template.bodyTemplate
  })
  showTemplateEditor.value = true
}

function closeTemplateEditor() {
  showTemplateEditor.value = false
  resetTemplateEditor()
}

async function saveTemplate() {
  templateSaving.value = true
  templateOperationError.value = ''
  try {
    const editing = Boolean(editingTemplateId.value)
    await api(editing ? `/report-templates/${editingTemplateId.value}` : '/report-templates', {
      method: editing ? 'PUT' : 'POST',
      body: jsonBody({ siteId: currentSiteId(), ...templateForm })
    })
    notify(editing ? '报告模板已更新' : '报告模板已创建')
    closeTemplateEditor()
    templatePage.value = 1
    await Promise.all([loadTemplates(), loadReportChoices()])
  } catch (reason) {
    templateOperationError.value = formatApiError(reason)
  } finally {
    templateSaving.value = false
  }
}

async function toggleTemplate(template) {
  templateActionId.value = template.id
  templateOperationError.value = ''
  try {
    await api(`/report-templates/${template.id}/enabled`, {
      method: 'PATCH',
      body: jsonBody({ enabled: !template.enabled })
    })
    notify(`${template.name}已${template.enabled ? '停用' : '启用'}`)
    await Promise.all([loadTemplates(), loadReportChoices()])
  } catch (reason) {
    templateOperationError.value = formatApiError(reason)
  } finally {
    templateActionId.value = null
  }
}

function queryTemplates() {
  templatePage.value = 1
  loadTemplates()
}

async function loadReports() {
  if (!hasSite.value) return
  reportLoading.value = true
  reportError.value = ''
  try {
    const data = await api(`/reports${toQuery({
      siteId: currentSiteId(),
      status: reportStatusFilter.value,
      page: reportPage.value,
      pageSize: reportPageSize.value
    })}`)
    reports.value = data.items
    reportTotal.value = data.total
  } catch (reason) {
    reportError.value = formatApiError(reason)
  } finally {
    reportLoading.value = false
  }
}

async function loadReportChoices() {
  if (!hasSite.value) return
  choicesLoading.value = true
  choicesError.value = ''
  try {
    const siteId = currentSiteId()
    const [templateItems, channelItems] = await Promise.all([
      collectAllPages((page, pageSize) => api(`/report-templates${toQuery({
        siteId,
        enabled: true,
        page,
        pageSize
      })}`)),
      collectAllPages((page, pageSize) => api(`/push-channels${toQuery({ siteId, page, pageSize })}`))
    ])
    reportTemplates.value = templateItems
    reportChannels.value = channelItems
    if (!reportTemplates.value.some(template => Number(template.id) === Number(generateForm.templateId))) {
      generateForm.templateId = reportTemplates.value[0]?.id || ''
    }
    if (!readyChannels.value.some(channel => Number(channel.id) === Number(selectedChannelId.value))) {
      selectedChannelId.value = readyChannels.value[0]?.id || ''
    }
  } catch (reason) {
    reportTemplates.value = []
    reportChannels.value = []
    choicesError.value = formatApiError(reason)
  } finally {
    choicesLoading.value = false
  }
}

function queryReports() {
  reportPage.value = 1
  loadReports()
}

async function openGenerate() {
  reportOperationError.value = ''
  showGenerate.value = !showGenerate.value
  if (showGenerate.value) await loadReportChoices()
}

async function generateReport() {
  if (!generateForm.templateId) {
    reportOperationError.value = '请先选择一个已启用的报告模板。'
    return
  }
  generating.value = true
  reportOperationError.value = ''
  try {
    await api('/reports/generate', {
      method: 'POST',
      body: jsonBody({
        siteId: currentSiteId(),
        templateId: Number(generateForm.templateId),
        title: generateForm.title.trim() || null
      })
    })
    notify('安全报告草稿已生成')
    showGenerate.value = false
    Object.assign(generateForm, { templateId: reportTemplates.value[0]?.id || '', title: '' })
    reportPage.value = 1
    await loadReports()
  } catch (reason) {
    reportOperationError.value = formatApiError(reason)
  } finally {
    generating.value = false
  }
}

function startEditReport(report) {
  editingReportId.value = report.id
  Object.assign(reportForm, { title: report.title, content: report.content })
  reviewTarget.value = null
  deliveryTarget.value = null
}

function closeReportEditor() {
  editingReportId.value = null
  Object.assign(reportForm, { title: '', content: '' })
}

async function saveReport() {
  if (!editingReportId.value) return
  reportSaving.value = true
  reportOperationError.value = ''
  try {
    await api(`/reports/${editingReportId.value}`, {
      method: 'PUT',
      body: jsonBody(reportForm)
    })
    notify('报告草稿已更新')
    closeReportEditor()
    await loadReports()
  } catch (reason) {
    reportOperationError.value = formatApiError(reason)
  } finally {
    reportSaving.value = false
  }
}

async function submitReport(report) {
  reportActionId.value = report.id
  reportOperationError.value = ''
  try {
    await api(`/reports/${report.id}/submit`, { method: 'POST' })
    notify('报告已提交人工审核')
    await loadReports()
  } catch (reason) {
    reportOperationError.value = formatApiError(reason)
  } finally {
    reportActionId.value = null
  }
}

function openReportReview(report) {
  reviewTarget.value = report
  reviewNote.value = ''
  closeReportEditor()
  deliveryTarget.value = null
}

async function reviewReport(action) {
  if (!reviewTarget.value) return
  if (action === 'REJECT' && !reviewNote.value.trim()) {
    reportOperationError.value = '驳回时必须填写审核意见。'
    return
  }
  reportActionId.value = reviewTarget.value.id
  reportOperationError.value = ''
  try {
    await api(`/reports/${reviewTarget.value.id}/review`, {
      method: 'POST',
      body: jsonBody({ action, note: reviewNote.value.trim() || null })
    })
    notify(action === 'APPROVE' ? '报告已批准，可以选择渠道投递' : '报告已驳回')
    reviewTarget.value = null
    reviewNote.value = ''
    await loadReports()
  } catch (reason) {
    reportOperationError.value = formatApiError(reason)
  } finally {
    reportActionId.value = null
  }
}

async function loadDeliveries() {
  if (!deliveryTarget.value) return
  deliveryLoading.value = true
  deliveryError.value = ''
  try {
    const data = await api(`/reports/${deliveryTarget.value.id}/deliveries${toQuery({
      page: deliveryPage.value,
      pageSize: deliveryPageSize.value
    })}`)
    deliveries.value = data.items
    deliveryTotal.value = data.total
  } catch (reason) {
    deliveryError.value = formatApiError(reason)
  } finally {
    deliveryLoading.value = false
  }
}

async function openDelivery(report) {
  deliveryTarget.value = report
  deliveryPage.value = 1
  deliveries.value = []
  deliveryTotal.value = 0
  closeReportEditor()
  reviewTarget.value = null
  await loadReportChoices()
  selectedChannelId.value = readyChannels.value[0]?.id || ''
  await loadDeliveries()
}

function closeDelivery() {
  deliveryTarget.value = null
  selectedChannelId.value = ''
  deliveries.value = []
  deliveryTotal.value = 0
  deliveryError.value = ''
}

async function deliverReport() {
  if (!deliveryTarget.value || !selectedChannelId.value) {
    reportOperationError.value = '请选择一个已启用且服务端就绪的推送渠道。'
    return
  }
  delivering.value = true
  reportOperationError.value = ''
  try {
    const result = await api(`/reports/${deliveryTarget.value.id}/deliveries`, {
      method: 'POST',
      body: jsonBody({ channelId: Number(selectedChannelId.value) })
    })
    notify(result.status === 'SENT' ? '报告投递成功' : '投递已执行，但渠道返回失败', result.status === 'SENT' ? 'info' : 'danger')
    deliveryPage.value = 1
    await Promise.all([loadDeliveries(), loadReports()])
  } catch (reason) {
    reportOperationError.value = formatApiError(reason)
  } finally {
    delivering.value = false
  }
}

function deliveryStatusLabel(status) {
  return ({ PENDING: '等待发送', SENT: '已发送', FAILED: '发送失败' })[status] || '未知状态'
}

function deliveryStatusTone(status) {
  if (status === 'SENT') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'warning'
}

function resetForSiteChange() {
  templatePage.value = 1
  templateOperationError.value = ''
  closeTemplateEditor()
  reportPage.value = 1
  reportOperationError.value = ''
  reportTemplates.value = []
  reportChannels.value = []
  showGenerate.value = false
  closeReportEditor()
  reviewTarget.value = null
  reviewNote.value = ''
  closeDelivery()
}

function loadActiveModule() {
  if (!hasSite.value) return
  if (activeModule.value === 'templates') loadTemplates()
  if (activeModule.value === 'reports') {
    loadReports()
    loadReportChoices()
  }
}

watch(activeModule, loadActiveModule, { immediate: true })
watch(() => store.state.siteId, () => {
  resetForSiteChange()
  loadActiveModule()
})
</script>

<template>
  <section class="governance-page">
    <header class="page-heading governance-heading">
      <div>
        <div class="folio"><svg viewBox="0 0 24 24"><use href="#app-icon-governance" /></svg></div>
        <h1>知识与报告治理</h1>
        <p>知识、模板、审核与推送均绑定当前工地，所有变更由服务端保存并写入审计日志。</p>
      </div>
      <div class="governance-heading-note">
        <span>当前权限</span>
        <strong>{{ isAdmin ? '管理员审核权限' : '业务编辑权限' }}</strong>
      </div>
    </header>

    <nav class="governance-tabs" aria-label="知识与报告治理模块">
      <button
        v-for="module in modules"
        :key="module.id"
        class="governance-tab"
        :class="{ active: activeModule === module.id }"
        type="button"
        :aria-current="activeModule === module.id ? 'page' : undefined"
        @click="activeModule = module.id"
      >
        <span>{{ module.index }}</span>
        <strong>{{ module.label }}</strong>
        <small>{{ module.summary }}</small>
      </button>
    </nav>

    <PageState
      v-if="!hasSite"
      title="暂无可用工地"
      message="当前账号没有可访问的工地，无法加载治理数据。"
    />

    <KnowledgeWorkspace
      v-else-if="activeModule === 'knowledge'"
      :site-id="store.state.siteId"
    />

    <section v-else-if="activeModule === 'templates'" class="governance-workspace" aria-labelledby="template-workspace-title">
      <div class="workspace-toolbar">
        <div>
          <h2 id="template-workspace-title">报告模板</h2>
          <p>模板只接受服务端支持的实时快照字段，停用后不能用于生成新报告。</p>
        </div>
        <button class="button button-primary" type="button" @click="showTemplateEditor ? closeTemplateEditor() : startCreateTemplate()">
          {{ showTemplateEditor ? '取消编辑' : '新建报告模板' }}
        </button>
      </div>

      <form v-if="showTemplateEditor" class="panel governance-editor" @submit.prevent="saveTemplate">
        <div class="panel-header">
          <div>
            <h3>{{ editingTemplateId ? '编辑报告模板' : '新建报告模板' }}</h3>
            <p>生成报告时，服务端会用当前工地快照替换模板字段。</p>
          </div>
        </div>
        <div class="governance-form-grid">
          <label class="field"><span>模板名称</span><input v-model.trim="templateForm.name" maxlength="120" required /></label>
          <label class="field"><span>说明</span><input v-model.trim="templateForm.description" maxlength="500" /></label>
          <label class="field governance-field-wide">
            <span>模板正文</span>
            <textarea v-model.trim="templateForm.bodyTemplate" rows="12" maxlength="50000" required />
          </label>
        </div>
        <div class="template-fields" aria-label="支持的模板字段">
          <span>支持字段</span>
          <button
            v-for="token in templateFieldTokens"
            :key="token"
            type="button"
            :title="`将 ${token} 插入模板末尾`"
            @click="templateForm.bodyTemplate += `${templateForm.bodyTemplate ? '\n' : ''}${token}`"
          >{{ token }}</button>
        </div>
        <div class="governance-form-actions">
          <button class="button button-primary" type="submit" :disabled="templateSaving">{{ templateSaving ? '正在保存' : '保存模板' }}</button>
          <button class="button button-secondary" type="button" @click="closeTemplateEditor">取消</button>
        </div>
      </form>

      <section class="panel governance-list-panel">
        <form class="filter-bar" @submit.prevent="queryTemplates">
          <label class="field">
            <span>启用状态</span>
            <select v-model="templateEnabledFilter">
              <option value="">全部状态</option>
              <option value="true">启用</option>
              <option value="false">停用</option>
            </select>
          </label>
          <button class="button button-secondary" type="submit">查询</button>
        </form>
        <p v-if="templateOperationError" class="form-error governance-operation-error" role="alert">{{ templateOperationError }}</p>
        <div v-if="templateLoading" class="loading-bar">正在加载报告模板</div>
        <PageState v-else-if="templateError" title="报告模板加载失败" :message="templateError" action="重试" @action="loadTemplates" />
        <div v-else class="data-table-wrap">
          <table class="data-table governance-table">
            <caption class="sr-only">报告模板列表</caption>
            <thead><tr><th>模板</th><th>状态</th><th>更新人</th><th>更新时间</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="template in templates" :key="template.id">
                <td><span class="cell-primary">{{ template.name }}</span><span class="cell-secondary">{{ template.description || '未填写说明' }}</span></td>
                <td><StatusBadge :label="template.enabled ? '启用' : '停用'" :tone="statusTone(template.enabled ? 'ONLINE' : 'OFFLINE')" /></td>
                <td>{{ template.updatedByName }}</td>
                <td class="tabular">{{ formatDate(template.updatedAt) }}</td>
                <td><div class="table-actions"><button class="button button-secondary button-small" type="button" :disabled="templateActionId === template.id" @click="startEditTemplate(template)">编辑</button><button class="button button-secondary button-small" type="button" :disabled="templateActionId === template.id" @click="toggleTemplate(template)">{{ template.enabled ? '停用' : '启用' }}</button></div></td>
              </tr>
            </tbody>
          </table>
          <PageState v-if="!templates.length" title="暂无报告模板" message="当前工地与筛选条件下没有报告模板。" />
          <PaginationBar v-if="templateTotal" v-model:page="templatePage" v-model:page-size="templatePageSize" :total="templateTotal" aria-label="报告模板分页" @change="loadTemplates" />
        </div>
      </section>
    </section>

    <section v-else-if="activeModule === 'reports'" class="governance-workspace" aria-labelledby="report-workspace-title">
      <div class="workspace-toolbar">
        <div>
          <h2 id="report-workspace-title">安全报告</h2>
          <p>报告由当前工地快照生成，经人工审核批准后才能投递。</p>
        </div>
        <button class="button button-primary" type="button" @click="openGenerate">{{ showGenerate ? '取消生成' : '生成报告' }}</button>
      </div>

      <form v-if="showGenerate" class="panel governance-editor" @submit.prevent="generateReport">
        <div class="panel-header"><div><h3>从模板生成报告草稿</h3><p>标题留空时由服务端按工地与日期生成。</p></div></div>
        <div class="governance-form-grid">
          <label class="field"><span>报告模板</span><select v-model="generateForm.templateId" required><option value="" disabled>{{ choicesLoading ? '正在加载模板' : '请选择模板' }}</option><option v-for="template in reportTemplates" :key="template.id" :value="template.id">{{ template.name }}</option></select></label>
          <label class="field"><span>报告标题（可选）</span><input v-model.trim="generateForm.title" maxlength="160" /></label>
        </div>
        <p v-if="choicesError" class="form-error" role="alert">可用模板加载失败：{{ choicesError }}</p>
        <PageState v-else-if="!choicesLoading && !reportTemplates.length" title="没有可用模板" message="请先创建并启用报告模板。" />
        <div class="governance-form-actions"><button class="button button-primary" type="submit" :disabled="generating || choicesLoading || !reportTemplates.length">{{ generating ? '正在生成' : '生成草稿' }}</button></div>
      </form>

      <form v-if="editingReportId" class="panel governance-editor" @submit.prevent="saveReport">
        <div class="panel-header"><div><h3>编辑报告草稿</h3><p>保存后报告回到草稿状态，需要重新提交人工审核。</p></div></div>
        <div class="governance-form-grid">
          <label class="field governance-field-wide"><span>报告标题</span><input v-model.trim="reportForm.title" maxlength="160" required /></label>
          <label class="field governance-field-wide"><span>报告正文</span><textarea v-model.trim="reportForm.content" rows="14" maxlength="50000" required /></label>
        </div>
        <div class="governance-form-actions"><button class="button button-primary" type="submit" :disabled="reportSaving">{{ reportSaving ? '正在保存' : '保存草稿' }}</button><button class="button button-secondary" type="button" @click="closeReportEditor">取消</button></div>
      </form>

      <form v-if="reviewTarget" class="panel review-desk" @submit.prevent="reviewReport('APPROVE')">
        <div><span class="review-desk-index">人工审核</span><h3>{{ reviewTarget.title }}</h3><p>批准后报告内容锁定并开放投递；驳回时必须填写审核意见。</p></div>
        <label class="field"><span>审核意见</span><textarea v-model.trim="reviewNote" rows="4" maxlength="500" /></label>
        <div class="table-actions"><button class="button button-primary" type="submit" :disabled="reportActionId === reviewTarget.id">批准报告</button><button class="button button-secondary" type="button" :disabled="reportActionId === reviewTarget.id" @click="reviewReport('REJECT')">驳回</button><button class="button button-secondary" type="button" @click="reviewTarget = null">取消</button></div>
      </form>

      <section v-if="deliveryTarget" class="panel delivery-desk" aria-labelledby="delivery-desk-title">
        <div class="panel-header"><div><span class="review-desk-index">投递控制</span><h3 id="delivery-desk-title">{{ deliveryTarget.title }}</h3><p>只显示当前工地已启用的渠道；Webhook 还必须通过服务端白名单与凭据检查。</p></div><button class="button button-secondary button-small" type="button" @click="closeDelivery">关闭</button></div>
        <form class="delivery-form" @submit.prevent="deliverReport">
          <label class="field"><span>推送渠道</span><select v-model="selectedChannelId" required><option value="" disabled>{{ choicesLoading ? '正在加载渠道' : '请选择渠道' }}</option><option v-for="channel in enabledChannels" :key="channel.id" :value="channel.id" :disabled="!channelReadyForDelivery(channel)">{{ channel.name }} · {{ channel.type }}{{ channelReadyForDelivery(channel) ? '' : ' · 未就绪' }}</option></select></label>
          <button class="button button-primary" type="submit" :disabled="delivering || choicesLoading || !readyChannels.length">{{ delivering ? '正在投递' : '立即投递' }}</button>
        </form>
        <p v-if="choicesError" class="form-error" role="alert">渠道加载失败：{{ choicesError }}</p>
        <PageState v-else-if="!choicesLoading && !enabledChannels.length" title="没有已启用渠道" message="请先在推送渠道模块创建或启用渠道。" />
        <PageState v-else-if="!choicesLoading && enabledChannels.length && !readyChannels.length" title="渠道尚未就绪" message="已启用渠道未通过服务端运行时或凭据检查。" />
        <div v-if="deliveryLoading" class="loading-bar">正在加载投递记录</div>
        <PageState v-else-if="deliveryError" title="投递记录加载失败" :message="deliveryError" action="重试" @action="loadDeliveries" />
        <div v-else-if="deliveries.length" class="data-table-wrap delivery-history">
          <table class="data-table governance-table">
            <caption class="sr-only">报告投递记录</caption>
            <thead><tr><th>渠道</th><th>结果</th><th>尝试</th><th>HTTP</th><th>完成时间</th><th>说明</th></tr></thead>
            <tbody><tr v-for="delivery in deliveries" :key="delivery.id"><td><span class="cell-primary">{{ delivery.channelName }}</span><span class="cell-secondary">{{ delivery.channelType }}</span></td><td><StatusBadge :label="deliveryStatusLabel(delivery.status)" :tone="deliveryStatusTone(delivery.status)" /></td><td class="tabular">{{ delivery.attemptCount }}</td><td class="tabular">{{ delivery.httpStatus || '—' }}</td><td class="tabular">{{ formatDate(delivery.completedAt || delivery.createdAt) }}</td><td>{{ delivery.errorMessage || '—' }}</td></tr></tbody>
          </table>
          <PaginationBar v-if="deliveryTotal" v-model:page="deliveryPage" v-model:page-size="deliveryPageSize" :total="deliveryTotal" aria-label="投递记录分页" @change="loadDeliveries" />
        </div>
        <PageState v-else-if="!deliveryLoading && !deliveryError" title="暂无投递记录" message="这份报告还没有执行过投递。" />
      </section>

      <section class="panel governance-list-panel">
        <form class="filter-bar" @submit.prevent="queryReports">
          <label class="field"><span>审核状态</span><select v-model="reportStatusFilter"><option value="">全部状态</option><option v-for="status in REPORT_STATUSES" :key="status" :value="status">{{ governanceStatusLabel(status) }}</option></select></label>
          <button class="button button-secondary" type="submit">查询</button>
        </form>
        <div class="status-track" aria-label="报告审核流程"><span v-for="status in REPORT_STATUSES" :key="status" :class="{ active: reportStatusFilter === status }">{{ governanceStatusLabel(status) }}</span></div>
        <p v-if="reportOperationError" class="form-error governance-operation-error" role="alert">{{ reportOperationError }}</p>
        <div v-if="reportLoading" class="loading-bar">正在加载安全报告</div>
        <PageState v-else-if="reportError" title="安全报告加载失败" :message="reportError" action="重试" @action="loadReports" />
        <div v-else class="data-table-wrap">
          <table class="data-table governance-table report-table">
            <caption class="sr-only">安全报告列表</caption>
            <thead><tr><th>报告</th><th>模板</th><th>状态</th><th>创建人</th><th>更新时间</th><th>投递</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="report in reports" :key="report.id">
                <td><span class="cell-primary">{{ report.title }}</span><span class="cell-secondary">{{ report.code }}<template v-if="report.reviewNote"> · 审核意见：{{ report.reviewNote }}</template></span></td>
                <td>{{ report.templateName || '模板已移除' }}</td>
                <td><StatusBadge :label="governanceStatusLabel(report.status)" :tone="governanceStatusTone(report.status)" /></td>
                <td>{{ report.createdByName }}</td>
                <td class="tabular">{{ formatDate(report.updatedAt) }}</td>
                <td class="tabular">{{ report.deliveryCount }}</td>
                <td><div class="table-actions"><button v-if="reportActions(report.status, isAdmin).includes('EDIT')" class="button button-secondary button-small" type="button" :disabled="reportActionId === report.id" @click="startEditReport(report)">编辑</button><button v-if="reportActions(report.status, isAdmin).includes('SUBMIT')" class="button button-secondary button-small" type="button" :disabled="reportActionId === report.id" @click="submitReport(report)">提交审核</button><button v-if="reportActions(report.status, isAdmin).includes('REVIEW')" class="button button-primary button-small" type="button" :disabled="reportActionId === report.id" @click="openReportReview(report)">审核</button><button v-if="reportActions(report.status, isAdmin).includes('DELIVER') || report.deliveryCount" class="button button-secondary button-small" type="button" @click="openDelivery(report)">{{ report.deliveryCount ? '投递记录' : '投递' }}</button></div></td>
              </tr>
            </tbody>
          </table>
          <PageState v-if="!reports.length" title="暂无安全报告" message="当前工地与筛选条件下没有安全报告。" />
          <PaginationBar v-if="reportTotal" v-model:page="reportPage" v-model:page-size="reportPageSize" :total="reportTotal" aria-label="安全报告分页" @change="loadReports" />
        </div>
      </section>
    </section>

    <ChannelWorkspace
      v-else-if="activeModule === 'channels'"
      :site-id="store.state.siteId"
    />
  </section>
</template>

<style scoped>
.governance-page {
  --governance-blue: #002fa7;
  --governance-ink: #111318;
  --governance-muted: #616875;
  --governance-line: #d9dde5;
  color: var(--governance-ink);
}

.governance-heading {
  align-items: end;
}

.governance-heading-note {
  min-width: 220px;
  padding: 14px 0 14px 20px;
  border-left: 1px solid var(--governance-blue);
}

.governance-heading-note span,
.governance-heading-note strong {
  display: block;
}

.governance-heading-note span {
  margin-bottom: 5px;
  color: var(--governance-muted);
  font-size: 12px;
}

.governance-tabs {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-bottom: 28px;
  border-top: 1px solid var(--governance-line);
  border-left: 1px solid var(--governance-line);
  background: #fff;
}

.governance-tab {
  min-height: 110px;
  padding: 16px;
  border: 0;
  border-right: 1px solid var(--governance-line);
  border-bottom: 1px solid var(--governance-line);
  background: #fff;
  color: var(--governance-ink);
  text-align: left;
  cursor: pointer;
}

.governance-tab > span,
.governance-tab > strong,
.governance-tab > small {
  display: block;
}

.governance-tab > span {
  color: var(--governance-blue);
  font-size: 26px;
  font-weight: 700;
  line-height: 1;
}

.governance-tab > strong {
  margin-top: 12px;
  font-size: 15px;
}

.governance-tab > small {
  margin-top: 4px;
  color: var(--governance-muted);
  font-size: 12px;
}

.governance-tab.active {
  background: var(--governance-blue);
  color: #fff;
}

.governance-tab.active > span,
.governance-tab.active > small {
  color: #fff;
}

.governance-workspace,
:deep(.governance-workspace) {
  display: grid;
  gap: 20px;
}

.workspace-toolbar,
:deep(.workspace-toolbar) {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--governance-line);
}

.workspace-toolbar h2,
:deep(.workspace-toolbar h2) {
  margin: 0;
  font-size: 24px;
}

.workspace-toolbar p,
:deep(.workspace-toolbar p) {
  max-width: 760px;
  margin: 6px 0 0;
  color: var(--governance-muted);
}

.governance-form-grid,
:deep(.governance-form-grid) {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px 20px;
}

.governance-field-wide,
:deep(.governance-field-wide) {
  grid-column: 1 / -1;
}

.governance-form-actions,
:deep(.governance-form-actions) {
  display: flex;
  gap: 10px;
  margin-top: 18px;
}

.template-fields {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0;
  margin-top: 16px;
  border-top: 1px solid var(--governance-line);
  border-left: 1px solid var(--governance-line);
}

.template-fields > span,
.template-fields button {
  min-height: 38px;
  padding: 9px 12px;
  border: 0;
  border-right: 1px solid var(--governance-line);
  border-bottom: 1px solid var(--governance-line);
  background: #fff;
  color: var(--governance-ink);
  font: inherit;
  font-size: 12px;
}

.template-fields > span {
  color: var(--governance-muted);
}

.template-fields button {
  cursor: pointer;
}

.template-fields button:hover,
.template-fields button:focus-visible {
  background: #f7f7f8;
  color: var(--governance-blue);
}

.status-track,
:deep(.status-track) {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  margin-top: 16px;
  border-top: 1px solid var(--governance-line);
  border-left: 1px solid var(--governance-line);
}

.status-track span,
:deep(.status-track span) {
  padding: 9px 12px;
  border-right: 1px solid var(--governance-line);
  border-bottom: 1px solid var(--governance-line);
  color: var(--governance-muted);
  font-size: 12px;
}

.status-track span.active,
:deep(.status-track span.active) {
  background: var(--governance-blue);
  color: #fff;
}

.review-desk,
:deep(.review-desk),
.delivery-desk {
  border-left: 4px solid var(--governance-blue);
}

.review-desk,
:deep(.review-desk) {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) minmax(300px, 1fr) auto;
  align-items: end;
  gap: 20px;
}

.review-desk h3,
:deep(.review-desk h3),
.delivery-desk h3 {
  margin: 5px 0 6px;
}

.review-desk p,
:deep(.review-desk p),
.delivery-desk p {
  margin: 0;
  color: var(--governance-muted);
}

.review-desk-index,
:deep(.review-desk-index) {
  color: var(--governance-blue);
  font-size: 12px;
  font-weight: 700;
}

.delivery-form {
  display: grid;
  grid-template-columns: minmax(280px, 1fr) auto;
  align-items: end;
  gap: 12px;
  max-width: 720px;
  margin: 18px 0;
}

.delivery-history {
  margin-top: 18px;
  border-top: 1px solid var(--governance-line);
}

.governance-table,
:deep(.governance-table) {
  min-width: 880px;
}

.report-table {
  min-width: 1080px;
}

.governance-operation-error,
:deep(.governance-operation-error) {
  margin: 14px 0 0;
}

:deep(.runtime-strip) {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-top: 1px solid var(--governance-line);
  border-left: 1px solid var(--governance-line);
}

:deep(.runtime-strip > div) {
  padding: 14px;
  border-right: 1px solid var(--governance-line);
  border-bottom: 1px solid var(--governance-line);
}

:deep(.runtime-strip span),
:deep(.runtime-strip strong) {
  display: block;
}

:deep(.runtime-strip span) {
  margin-bottom: 4px;
  color: var(--governance-muted);
  font-size: 12px;
}

@media (max-width: 1100px) {
  .review-desk,
  :deep(.review-desk) {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
