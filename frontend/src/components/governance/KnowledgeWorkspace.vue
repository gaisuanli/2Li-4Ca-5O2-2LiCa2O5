<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { api, formatApiError, jsonBody, toQuery } from '../../api'
import { formatDate } from '../../format'
import {
  governanceStatusLabel,
  governanceStatusTone,
  knowledgeActions,
  KNOWLEDGE_STATUSES
} from '../../governance'
import PageState from '../PageState.vue'
import PaginationBar from '../PaginationBar.vue'
import StatusBadge from '../StatusBadge.vue'

const props = defineProps({ siteId: { type: [Number, String], required: true } })
const store = useStore()
const documents = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const loading = ref(true)
const error = ref('')
const operationError = ref('')
const saving = ref(false)
const actionId = ref(null)
const showEditor = ref(false)
const editingId = ref(null)
const reviewTarget = ref(null)
const reviewNote = ref('')
const filters = reactive({ keyword: '', status: '' })
const appliedFilters = reactive({ keyword: '', status: '' })
const form = reactive({ title: '', category: '', sourceReference: '', content: '' })
const isAdmin = computed(() => store.state.user?.role === 'ADMIN')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api(`/knowledge-documents${toQuery({
      siteId: props.siteId,
      ...appliedFilters,
      page: page.value,
      pageSize: pageSize.value
    })}`)
    documents.value = data.items
    total.value = data.total
  } catch (reason) {
    error.value = formatApiError(reason)
  } finally {
    loading.value = false
  }
}

function query() {
  Object.assign(appliedFilters, filters)
  page.value = 1
  load()
}

function resetEditor() {
  editingId.value = null
  Object.assign(form, { title: '', category: '', sourceReference: '', content: '' })
}

function startCreate() {
  resetEditor()
  showEditor.value = true
}

function startEdit(document) {
  editingId.value = document.id
  Object.assign(form, {
    title: document.title,
    category: document.category,
    sourceReference: document.sourceReference || '',
    content: document.content
  })
  showEditor.value = true
}

function closeEditor() {
  showEditor.value = false
  resetEditor()
}

async function save() {
  saving.value = true
  operationError.value = ''
  try {
    const payload = jsonBody({ siteId: Number(props.siteId), ...form })
    await api(editingId.value ? `/knowledge-documents/${editingId.value}` : '/knowledge-documents', {
      method: editingId.value ? 'PUT' : 'POST',
      body: payload
    })
    store.dispatch('notify', { tone: 'info', message: editingId.value ? '知识文档草稿已更新' : '知识文档草稿已创建' })
    closeEditor()
    page.value = 1
    await load()
  } catch (reason) {
    operationError.value = formatApiError(reason)
  } finally {
    saving.value = false
  }
}

async function runAction(document, path, successMessage) {
  actionId.value = document.id
  operationError.value = ''
  try {
    await api(`/knowledge-documents/${document.id}/${path}`, { method: 'POST' })
    store.dispatch('notify', { tone: 'info', message: successMessage })
    await load()
  } catch (reason) {
    operationError.value = formatApiError(reason)
  } finally {
    actionId.value = null
  }
}

function openReview(document) {
  reviewTarget.value = document
  reviewNote.value = ''
}

async function review(action) {
  if (!reviewTarget.value) return
  if (action === 'REJECT' && !reviewNote.value.trim()) {
    operationError.value = '驳回时必须填写审核意见。'
    return
  }
  actionId.value = reviewTarget.value.id
  operationError.value = ''
  try {
    await api(`/knowledge-documents/${reviewTarget.value.id}/review`, {
      method: 'POST',
      body: jsonBody({ action, note: reviewNote.value.trim() || null })
    })
    store.dispatch('notify', { tone: 'info', message: action === 'APPROVE' ? '知识文档已发布' : '知识文档已驳回' })
    reviewTarget.value = null
    reviewNote.value = ''
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
  reviewTarget.value = null
  closeEditor()
  load()
})
</script>

<template>
  <section class="governance-workspace" aria-labelledby="knowledge-workspace-title">
    <div class="workspace-toolbar">
      <div>
        <h2 id="knowledge-workspace-title">受控知识库</h2>
        <p>只有管理员批准并发布的当前工地文档会进入 AI Agent 检索。</p>
      </div>
      <button class="button button-primary" type="button" @click="showEditor ? closeEditor() : startCreate()">
        {{ showEditor ? '取消编辑' : '新建知识文档' }}
      </button>
    </div>

    <form v-if="showEditor" class="panel governance-editor" @submit.prevent="save">
      <div class="panel-header">
        <div><h3>{{ editingId ? '编辑知识文档草稿' : '新建知识文档草稿' }}</h3><p>来源说明用于追溯，正文在审核通过前不会参与问答。</p></div>
      </div>
      <div class="governance-form-grid">
        <label class="field"><span>标题</span><input v-model.trim="form.title" maxlength="160" required /></label>
        <label class="field"><span>分类</span><input v-model.trim="form.category" maxlength="80" required /></label>
        <label class="field governance-field-wide"><span>来源说明</span><input v-model.trim="form.sourceReference" maxlength="500" /></label>
        <label class="field governance-field-wide"><span>正文</span><textarea v-model.trim="form.content" rows="10" maxlength="30000" required /></label>
      </div>
      <div class="governance-form-actions">
        <button class="button button-primary" type="submit" :disabled="saving">{{ saving ? '正在保存' : '保存草稿' }}</button>
        <button class="button button-secondary" type="button" @click="closeEditor">取消</button>
      </div>
    </form>

    <section class="panel governance-list-panel">
      <form class="filter-bar" role="search" @submit.prevent="query">
        <label class="field"><span>关键字</span><input v-model.trim="filters.keyword" type="search" placeholder="标题、分类或来源" /></label>
        <label class="field"><span>生命周期状态</span><select v-model="filters.status"><option value="">全部状态</option><option v-for="status in KNOWLEDGE_STATUSES" :key="status" :value="status">{{ governanceStatusLabel(status) }}</option></select></label>
        <button class="button button-secondary" type="submit">查询</button>
      </form>

      <div class="status-track" aria-label="知识文档生命周期">
        <span v-for="status in KNOWLEDGE_STATUSES" :key="status" :class="{ active: appliedFilters.status === status }">{{ governanceStatusLabel(status) }}</span>
      </div>

      <p v-if="operationError" class="form-error governance-operation-error" role="alert">{{ operationError }}</p>
      <div v-if="loading" class="loading-bar">正在加载知识文档</div>
      <PageState v-else-if="error" title="知识文档加载失败" :message="error" action="重试" @action="load" />
      <div v-else class="data-table-wrap">
        <table class="data-table governance-table">
          <caption class="sr-only">受控知识文档列表</caption>
          <thead><tr><th>文档</th><th>版本</th><th>状态</th><th>责任人</th><th>更新时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="document in documents" :key="document.id">
              <td><span class="cell-primary">{{ document.title }}</span><span class="cell-secondary">{{ document.category }}<template v-if="document.sourceReference"> · {{ document.sourceReference }}</template></span></td>
              <td class="tabular">V{{ document.version }}</td>
              <td><StatusBadge :label="governanceStatusLabel(document.status)" :tone="governanceStatusTone(document.status)" /></td>
              <td><span class="cell-primary">{{ document.createdByName }}</span><span class="cell-secondary">审核：{{ document.reviewedByName || '—' }}</span></td>
              <td class="tabular">{{ formatDate(document.updatedAt) }}</td>
              <td>
                <div class="table-actions">
                  <button v-if="knowledgeActions(document.status, isAdmin).includes('EDIT')" class="button button-secondary button-small" type="button" :disabled="actionId === document.id" @click="startEdit(document)">编辑</button>
                  <button v-if="knowledgeActions(document.status, isAdmin).includes('SUBMIT')" class="button button-secondary button-small" type="button" :disabled="actionId === document.id" @click="runAction(document, 'submit', '知识文档已提交审核')">提交审核</button>
                  <button v-if="knowledgeActions(document.status, isAdmin).includes('REVIEW')" class="button button-primary button-small" type="button" :disabled="actionId === document.id" @click="openReview(document)">审核</button>
                  <button v-if="knowledgeActions(document.status, isAdmin).includes('ARCHIVE')" class="button button-secondary button-small" type="button" :disabled="actionId === document.id" @click="runAction(document, 'archive', '知识文档已归档')">归档</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
        <PageState v-if="!documents.length" title="暂无知识文档" message="当前工地与筛选条件下没有知识文档。" />
        <PaginationBar v-if="total" v-model:page="page" v-model:page-size="pageSize" :total="total" aria-label="知识文档分页" @change="load" />
      </div>
    </section>

    <form v-if="reviewTarget" class="panel review-desk" @submit.prevent="review('APPROVE')">
      <div><span class="review-desk-index">人工审核</span><h3>{{ reviewTarget.title }}</h3><p>审核通过后立即进入当前工地知识检索；驳回时必须记录原因。</p></div>
      <label class="field"><span>审核意见</span><textarea v-model.trim="reviewNote" rows="4" maxlength="500" /></label>
      <div class="table-actions">
        <button class="button button-primary" type="submit" :disabled="actionId === reviewTarget.id">批准并发布</button>
        <button class="button button-secondary" type="button" :disabled="actionId === reviewTarget.id" @click="review('REJECT')">驳回</button>
        <button class="button button-secondary" type="button" @click="reviewTarget = null">取消</button>
      </div>
    </form>
  </section>
</template>
