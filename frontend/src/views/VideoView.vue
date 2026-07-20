<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { api, formatApiError, toQuery } from '../api'
import PageState from '../components/PageState.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { formatDate, statusLabels, statusTone } from '../format'

const store = useStore()
const cameras = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const selectedId = ref(null)
const loading = ref(true)
const error = ref('')
const filters = reactive({ keyword: '', online: '' })
const appliedFilters = reactive({ keyword: '', online: '' })
const playbackErrors = reactive({})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api(`/cameras${toQuery({
      siteId: store.state.siteId,
      ...appliedFilters,
      page: page.value,
      pageSize: pageSize.value
    })}`)
    cameras.value = data.items
    total.value = data.total
    if (!cameras.value.some(camera => camera.id === selectedId.value)) selectedId.value = cameras.value[0]?.id || null
  } catch (reason) {
    error.value = formatApiError(reason)
  } finally {
    loading.value = false
  }
}

function queryCameras() {
  Object.assign(appliedFilters, filters)
  page.value = 1
  load()
}

function markPlaybackError(cameraId) {
  playbackErrors[cameraId] = true
}

onMounted(load)
watch(() => store.state.siteId, () => {
  selectedId.value = null
  page.value = 1
  Object.keys(playbackErrors).forEach(key => delete playbackErrors[key])
  load()
})
</script>

<template>
  <section>
    <header class="page-heading"><div><div class="folio">06 / 14</div><h1>视频监控</h1><p>按摄像头通道展示播放配置和在线状态；可播放、未配置与离线状态分别处理。</p></div><div class="heading-meta"><div><span>查询结果</span><strong>{{ total }} 路</strong></div><div><span>播放链路</span><strong>HLS 适配层</strong></div></div></header>
    <section class="panel video-list-panel">
      <form class="filter-bar" role="search" @submit.prevent="queryCameras">
        <label class="field"><span>关键字</span><input v-model.trim="filters.keyword" type="search" placeholder="通道编号或名称" /></label>
        <label class="field"><span>通道状态</span><select v-model="filters.online"><option value="">全部状态</option><option :value="true">在线</option><option :value="false">离线</option></select></label>
        <button class="button button-secondary" type="submit">查询</button>
      </form>
      <div v-if="loading" class="loading-bar">正在加载视频通道</div>
      <PageState v-else-if="error" title="视频通道加载失败" :message="error" action="重试" @action="load" />
      <PageState v-else-if="!cameras.length" title="暂无视频通道" message="当前筛选条件下没有摄像头。" />
      <div v-else class="video-grid panel-body">
        <article v-for="camera in cameras" :key="camera.id" class="video-card" :class="{ selected: selectedId === camera.id }" tabindex="0" @click="selectedId = camera.id" @keydown.enter="selectedId = camera.id">
          <div class="video-frame">
            <video v-if="camera.playbackStatus === 'READY' && !playbackErrors[camera.id]" class="video-player" controls preload="metadata" :src="camera.streamUrl" :aria-label="`${camera.name}视频流`" @error="markPlaybackError(camera.id)"></video>
            <div v-else class="video-placeholder">
              <strong v-if="camera.playbackStatus === 'OFFLINE'">摄像头离线</strong>
              <strong v-else-if="camera.playbackStatus === 'NOT_CONFIGURED'">视频流未配置</strong>
              <strong v-else>视频流加载失败</strong>
              <span v-if="camera.playbackStatus === 'OFFLINE'">最近画面 {{ formatDate(camera.lastFrameAt) }}</span>
              <span v-else-if="camera.playbackStatus === 'NOT_CONFIGURED'">配置经过鉴权的 HLS 地址后可播放</span>
              <span v-else>通道配置有效，但浏览器当前无法播放该流</span>
            </div>
            <span class="channel-code">{{ camera.code }}</span>
          </div>
          <div class="video-meta"><div><strong>{{ camera.name }}</strong><span>{{ camera.zoneName }}</span></div><StatusBadge :label="playbackErrors[camera.id] ? '播放失败' : (statusLabels[camera.playbackStatus] || '未知状态')" :tone="playbackErrors[camera.id] ? 'danger' : statusTone(camera.playbackStatus)" /></div>
        </article>
      </div>
      <PaginationBar v-if="total" v-model:page="page" v-model:page-size="pageSize" :total="total" aria-label="视频通道分页" @change="load" />
    </section>
  </section>
</template>
