<script setup>
import { onMounted, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { api, formatApiError, toQuery } from '../api'
import PageState from '../components/PageState.vue'
import PaginationBar from '../components/PaginationBar.vue'
import { auditActionLabels, auditObjectLabels, formatAuditDetail, formatDate } from '../format'

const store = useStore()
const logs = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const loading = ref(true)
const error = ref('')
async function load() { loading.value = true; error.value = ''; try { const data = await api(`/audit-logs${toQuery({ siteId: store.state.siteId, page: page.value, pageSize: pageSize.value })}`); logs.value = data.items; total.value = data.total } catch (reason) { error.value = formatApiError(reason) } finally { loading.value = false } }
onMounted(load)
watch(() => store.state.siteId, () => { page.value = 1; load() })
</script>

<template>
  <section>
    <header class="page-heading"><div><div class="folio">12 / 14</div><h1>审计日志</h1><p>记录设备维护、规则修改、风险复核、告警处置和喷淋控制等关键操作，并保留请求追踪编号。</p></div><button class="button button-secondary" @click="load">刷新</button></header>
    <div v-if="loading" class="loading-bar">正在加载审计日志</div>
    <PageState v-else-if="error" title="审计日志加载失败" :message="error" action="重试" @action="load" />
    <section v-else class="panel"><div class="data-table-wrap"><table class="data-table audit-table"><caption class="sr-only">审计日志</caption><thead><tr><th>时间</th><th>用户</th><th>操作</th><th>对象</th><th>说明</th><th>追踪编号</th></tr></thead><tbody><tr v-for="log in logs" :key="log.id"><td class="tabular">{{ formatDate(log.createdAt) }}</td><td>{{ log.username === 'system' ? '系统任务' : log.username }}</td><td><span class="cell-primary">{{ auditActionLabels[log.action] || '未识别操作' }}</span></td><td>{{ auditObjectLabels[log.objectType] || '未识别对象' }} / {{ log.objectId || '—' }}</td><td>{{ formatAuditDetail(log.detail) }}</td><td class="trace-cell" :title="log.traceId || undefined">{{ log.traceId || '—' }}</td></tr></tbody></table><PageState v-if="!logs.length" title="暂无审计记录" message="关键操作发生后会在此处留痕。" /><PaginationBar v-if="total" v-model:page="page" v-model:page-size="pageSize" :total="total" aria-label="审计日志分页" @change="load" /></div></section>
  </section>
</template>
