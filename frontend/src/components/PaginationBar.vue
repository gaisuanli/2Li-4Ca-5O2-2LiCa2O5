<script setup>
import { computed, watch } from 'vue'
import { clampPage, getPageCount, getVisiblePages } from '../pagination'

const props = defineProps({
  page: { type: Number, required: true },
  pageSize: { type: Number, required: true },
  total: { type: Number, required: true },
  pageSizes: { type: Array, default: () => [10, 20, 50] },
  ariaLabel: { type: String, default: '列表分页' }
})
const emit = defineEmits(['update:page', 'update:pageSize', 'change'])
const pageCount = computed(() => getPageCount(props.total, props.pageSize))
const currentPage = computed(() => clampPage(props.page, pageCount.value))
const pages = computed(() => getVisiblePages(currentPage.value, pageCount.value))

watch([() => props.page, pageCount], ([value, count]) => {
  const next = clampPage(value, count)
  if (next !== value) updatePagination(next, props.pageSize, 'bounds')
})

function updatePagination(nextPage, nextPageSize, reason) {
  if (nextPageSize !== props.pageSize) emit('update:pageSize', nextPageSize)
  if (nextPage !== props.page) emit('update:page', nextPage)
  emit('change', { page: nextPage, pageSize: nextPageSize, reason })
}

function setPage(value) {
  const next = Math.max(1, Math.min(value, pageCount.value))
  if (next === props.page) return
  updatePagination(next, props.pageSize, 'page')
}
function setPageSize(event) {
  const nextPageSize = Number(event.currentTarget.value)
  if (!Number.isFinite(nextPageSize) || nextPageSize <= 0) return
  updatePagination(1, nextPageSize, 'page-size')
}
</script>

<template>
  <nav class="pagination" :aria-label="ariaLabel">
    <span class="pagination-total">共 {{ total }} 条</span>
    <label class="pagination-size">
      <span>每页条数</span>
      <select :value="pageSize" aria-label="每页条数" @change="setPageSize">
        <option v-for="size in pageSizes" :key="size" :value="size">{{ size }}</option>
      </select>
    </label>
    <div class="pagination-actions">
      <button type="button" :disabled="currentPage <= 1" aria-label="转到首页" @click="setPage(1)">首页</button>
      <button type="button" :disabled="currentPage <= 1" aria-label="转到上一页" @click="setPage(currentPage - 1)">上一页</button>
      <button
        v-for="number in pages"
        :key="number"
        type="button"
        :class="{ active: number === currentPage }"
        :aria-label="`第 ${number} 页`"
        :aria-current="number === currentPage ? 'page' : undefined"
        @click="setPage(number)"
      >{{ number }}</button>
      <button type="button" :disabled="currentPage >= pageCount" aria-label="转到下一页" @click="setPage(currentPage + 1)">下一页</button>
      <button type="button" :disabled="currentPage >= pageCount" aria-label="转到末页" @click="setPage(pageCount)">末页</button>
    </div>
    <output class="pagination-pages" aria-live="polite" aria-atomic="true">{{ currentPage }} / {{ pageCount }} 页</output>
  </nav>
</template>
