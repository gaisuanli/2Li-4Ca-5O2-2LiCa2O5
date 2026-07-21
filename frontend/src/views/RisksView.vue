<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { api, formatApiError, jsonBody, toQuery } from '../api'
import PageState from '../components/PageState.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { formatDate, statusLabels, statusTone } from '../format'

const store = useStore()
const risks = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const status = ref('')
const selectedId = ref(null)
const note = ref('')
const loading = ref(true)
const error = ref('')
const evidenceFailed = ref(false)
const selected = computed(() => risks.value.find(item => item.id === selectedId.value))
const canReview = computed(() => ['ADMIN', 'SUPERVISOR'].includes(store.state.user?.role))

async function load() {
  loading.value = true; error.value = ''
  try {
    const data = await api(`/risks${toQuery({ siteId: store.state.siteId, status: status.value, page: page.value, pageSize: pageSize.value })}`)
    risks.value = data.items
    total.value = data.total
    if (!risks.value.some(item => item.id === selectedId.value)) selectedId.value = risks.value[0]?.id || null
  } catch (reason) { error.value = formatApiError(reason) }
  finally { loading.value = false }
}
async function review(action) {
  await api(`/risks/${selectedId.value}/review`, { method: 'POST', body: jsonBody({ action, note: note.value }) })
  store.dispatch('notify', { tone: action === 'CONFIRM' ? 'danger' : 'info', message: action === 'CONFIRM' ? '风险已确认并生成告警' : '风险已标记为误报' })
  note.value = ''; await load()
}
onMounted(load)
watch([status, () => store.state.siteId], () => { page.value = 1; load() })
watch(selectedId, () => { evidenceFailed.value = false })
</script>

<template>
  <section>
    <header class="page-heading"><div><div class="folio"><svg viewBox="0 0 24 24"><use href="#app-icon-risks" /></svg></div><h1>AI 风险</h1><p>AI 识别结果必须经过人工复核；确认有效后才生成告警，模型版本和置信度始终保留。</p></div><label class="field heading-select"><span>复核状态</span><select v-model="status"><option value="">全部状态</option><option value="PENDING_REVIEW">待复核</option><option value="CONFIRMED">已确认</option><option value="FALSE_POSITIVE">误报</option></select></label></header>
    <div v-if="loading" class="loading-bar">正在加载 AI 风险事件</div>
    <PageState v-else-if="error" title="风险事件加载失败" :message="error" action="重试" @action="load" />
    <div v-else class="risk-layout">
      <div class="risk-list">
        <button v-for="risk in risks" :key="risk.id" class="risk-item" :class="{ selected: risk.id === selectedId }" :aria-pressed="risk.id === selectedId" @click="selectedId = risk.id">
          <span class="risk-index">{{ String(risk.id).padStart(2, '0') }}</span>
          <span class="risk-main"><strong>{{ risk.riskType }}</strong><small>{{ risk.cameraName }} · {{ risk.zoneName }}</small></span>
          <span class="risk-score">{{ (Number(risk.confidence) * 100).toFixed(1) }}%</span>
          <StatusBadge :label="statusLabels[risk.status]" :tone="statusTone(risk.status)" />
        </button>
        <PageState v-if="!risks.length" title="暂无风险事件" message="当前筛选条件下没有 AI 风险记录。" />
        <PaginationBar v-if="total" v-model:page="page" v-model:page-size="pageSize" :total="total" aria-label="风险列表分页" @change="load" />
      </div>
      <aside class="panel risk-detail">
        <div class="panel-header"><div><h2>复核详情</h2><p>人工判断优先于模型结果</p></div></div>
        <template v-if="selected">
          <img v-if="selected.evidenceUrl && !evidenceFailed" class="risk-evidence" :src="selected.evidenceUrl" :alt="`${selected.riskType}识别证据`" loading="lazy" referrerpolicy="no-referrer" @error="evidenceFailed = true" />
          <div v-else class="evidence-placeholder"><strong>{{ evidenceFailed ? '证据图片加载失败' : '暂无证据图片' }}</strong><span>{{ evidenceFailed ? '当前地址无法访问，请稍后重试或联系系统管理员' : '当前记录未包含可查看的证据图片' }}</span></div>
          <div class="panel-body"><div class="risk-title-row"><div><span class="eyebrow">{{ selected.modelVersion }}</span><h3>{{ selected.riskType }}</h3></div><strong>{{ (Number(selected.confidence) * 100).toFixed(1) }}%</strong></div><dl class="detail-list"><div><dt>摄像头</dt><dd>{{ selected.cameraName }}</dd></div><div><dt>区域</dt><dd>{{ selected.zoneName }}</dd></div><div><dt>发生时间</dt><dd>{{ formatDate(selected.occurredAt) }}</dd></div><div><dt>复核状态</dt><dd>{{ statusLabels[selected.status] }}</dd></div><div v-if="selected.reviewNote"><dt>复核说明</dt><dd>{{ selected.reviewNote }}</dd></div></dl><form v-if="canReview && selected.status === 'PENDING_REVIEW'" class="review-form" @submit.prevent><label class="field"><span>复核说明</span><textarea v-model.trim="note" placeholder="填写现场核查情况"></textarea></label><div><button class="button button-primary" type="button" @click="review('CONFIRM')">确认有效</button><button class="button button-secondary" type="button" @click="review('FALSE_POSITIVE')">标记误报</button></div></form></div>
        </template>
        <PageState v-else title="尚未选择风险" message="从风险列表选择一项进行查看。" />
      </aside>
    </div>
  </section>
</template>
