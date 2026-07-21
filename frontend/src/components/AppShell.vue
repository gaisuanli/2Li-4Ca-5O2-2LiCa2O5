<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useStore } from 'vuex'
import { roleLabels } from '../format'
import StatusBadge from './StatusBadge.vue'

const store = useStore()
const route = useRoute()
const router = useRouter()
const loadingSession = ref(true)

const navSections = [
  {
    id: 'overview',
    label: '监控总览',
    items: [
      { index: '01', label: '综合首页', path: '/dashboard', icon: 'dashboard' },
      { index: '02', label: '设备总览', path: '/devices', icon: 'devices' },
      { index: '03', label: '塔吊分析', path: '/tower', icon: 'tower' },
      { index: '04', label: '大型设备监测', path: '/equipment', icon: 'equipment' },
      { index: '05', label: '环境分析', path: '/environment', icon: 'environment' },
      { index: '06', label: '视频监控', path: '/video', icon: 'video' }
    ]
  },
  {
    id: 'intelligence',
    label: '智能分析',
    items: [
      { index: '07', label: 'AI 风险', path: '/risks', icon: 'risks' },
      { index: '08', label: 'AI Agent 问答', path: '/agent', icon: 'agent' },
      { index: '09', label: '告警中心', path: '/alarms', icon: 'alarms' }
    ]
  },
  {
    id: 'administration',
    label: '系统管理',
    items: [
      { index: '10', label: '规则配置', path: '/rules', icon: 'rules', roles: ['ADMIN', 'SUPERVISOR'] },
      { index: '11', label: '用户管理', path: '/users', icon: 'users', roles: ['ADMIN'] },
      { index: '12', label: '审计日志', path: '/audit', icon: 'audit', roles: ['ADMIN'] }
    ]
  },
  {
    id: 'delivery',
    label: '治理与接入',
    items: [
      { index: '13', label: '知识与报告', path: '/governance', icon: 'governance', roles: ['ADMIN', 'SUPERVISOR'] },
      { index: '14', label: '集成验收', path: '/integrations', icon: 'integrations', roles: ['ADMIN', 'SUPERVISOR'] }
    ]
  }
]

const navGroups = computed(() => navSections
  .map(section => ({
    ...section,
    items: section.items.filter(item => !item.roles || item.roles.includes(store.state.user?.role))
  }))
  .filter(section => section.items.length > 0))

const currentNavItem = computed(() => navSections
  .flatMap(section => section.items)
  .find(item => item.path === route.path))

const currentPageIndex = computed(() => currentNavItem.value?.index || '00')
const currentPageTitle = computed(() => (
  route.matched.at(-1)?.meta.title
  || currentNavItem.value?.label
  || '建筑安全智能监控平台'
))
const currentSiteName = computed(() => (
  store.state.sites.find(site => Number(site.id) === Number(store.state.siteId))?.name
  || (loadingSession.value ? '工地数据加载中' : '暂无可用工地')
))
const siteOptionPlaceholder = computed(() => (
  loadingSession.value ? '正在加载工地列表' : '暂无可用工地'
))

const realtimeStates = {
  CONNECTED: { label: '实时通道已连接', tone: 'success' },
  CONNECTING: { label: '实时通道连接中', tone: 'warning' },
  DISCONNECTED: { label: '实时通道未连接', tone: 'danger' }
}
const realtime = computed(() => (
  realtimeStates[store.state.realtimeStatus]
  || { label: '实时通道状态未知', tone: 'neutral' }
))

onMounted(async () => {
  try {
    await store.dispatch('loadSession')
    const requiredRoles = route.matched.flatMap(record => record.meta.roles || [])
    if (requiredRoles.length && !requiredRoles.includes(store.state.user?.role)) {
      await router.replace('/dashboard')
    }
    store.dispatch('connectRealtime')
  } catch {
    store.commit('clearSession')
    router.replace('/login')
  } finally {
    loadingSession.value = false
  }
})

async function logout() {
  await store.dispatch('logout')
  router.replace('/login')
}
</script>

<template>
  <div
    class="app-shell"
    :data-route-index="currentPageIndex"
    :data-route-path="route.path"
  >
    <a class="skip-link" href="#main-content">跳到主要内容</a>

    <svg class="app-icon-sprite" aria-hidden="true" focusable="false" width="0" height="0">
      <defs>
        <symbol id="app-icon-brand" viewBox="0 0 24 24">
          <path d="M3 21V8l6-4 6 4v13M15 11h6v10M7 10h4M7 14h4M7 18h4M18 14h3M18 18h3M2 21h20" />
        </symbol>
        <symbol id="app-icon-dashboard" viewBox="0 0 24 24">
          <path d="M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z" />
        </symbol>
        <symbol id="app-icon-devices" viewBox="0 0 24 24">
          <path d="M5 3h14v18H5zM8 7h8M8 11h8M8 15h5M16 15h.01" />
        </symbol>
        <symbol id="app-icon-tower" viewBox="0 0 24 24">
          <path d="M9 21V5M6 21h6M7 17h4M7.5 13h3M8 9h2M4 5h16M14 5v3M20 5v7M18 12h4M9 5l3-3M5 5l4-3" />
        </symbol>
        <symbol id="app-icon-equipment" viewBox="0 0 24 24">
          <path d="M3 15h12l3 3H6zM7 15V9h6l3 6M13 9l3-5h3M16 4l4 3M7 21h10M8 18v3M16 18v3" />
        </symbol>
        <symbol id="app-icon-environment" viewBox="0 0 24 24">
          <path d="M4 14c7 0 12-4 15-10 1 8-3 15-10 15M4 20c3-6 7-9 13-12M3 6h5M2 10h4" />
        </symbol>
        <symbol id="app-icon-video" viewBox="0 0 24 24">
          <path d="M3 5h14v14H3zM17 9l4-3v12l-4-3zM7 9h6M7 13h4" />
        </symbol>
        <symbol id="app-icon-risks" viewBox="0 0 24 24">
          <path d="M12 3 2.5 20h19zM12 9v5M12 17h.01" />
        </symbol>
        <symbol id="app-icon-agent" viewBox="0 0 24 24">
          <path d="M4 4h16v13H9l-5 4zM8 9h8M8 13h5M17 2v4M15 4h4" />
        </symbol>
        <symbol id="app-icon-alarms" viewBox="0 0 24 24">
          <path d="M6 17h12l-2-3V9a4 4 0 0 0-8 0v5zM10 21h4M12 2v2M4 7 2 5M20 7l2-2" />
        </symbol>
        <symbol id="app-icon-rules" viewBox="0 0 24 24">
          <path d="M4 6h6M14 6h6M4 12h10M18 12h2M4 18h3M11 18h9M10 3v6M14 9v6M7 15v6M11 15v6" />
        </symbol>
        <symbol id="app-icon-users" viewBox="0 0 24 24">
          <path d="M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM2 21v-3c0-3 2.5-5 7-5s7 2 7 5v3M17 8a3 3 0 0 0 0-6M17 13c3.5 0 5 1.7 5 4v2" />
        </symbol>
        <symbol id="app-icon-audit" viewBox="0 0 24 24">
          <path d="M5 3h10l4 4v6M15 3v5h4M9 9h3M9 13h3M5 19H3V3h2M14 18a4 4 0 1 0 8 0 4 4 0 0 0-8 0ZM21 21l2 2" />
        </symbol>
        <symbol id="app-icon-governance" viewBox="0 0 24 24">
          <path d="M4 3h12a3 3 0 0 1 3 3v15H7a3 3 0 0 1-3-3zM7 3v18M10 8h6M10 12h6M10 16h4" />
        </symbol>
        <symbol id="app-icon-integrations" viewBox="0 0 24 24">
          <path d="M8 3v5M16 3v5M5 8h14v3a7 7 0 0 1-7 7v3M9 12h6" />
        </symbol>
        <symbol id="app-icon-logout" viewBox="0 0 24 24">
          <path d="M10 4H4v16h6M14 8l4 4-4 4M8 12h10" />
        </symbol>
      </defs>
    </svg>

    <aside class="sidebar" aria-label="应用导航">
      <div class="brand-block">
        <span class="brand-index" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <use href="#app-icon-brand" />
          </svg>
        </span>
        <div class="brand-copy">
          <strong>建筑安全</strong>
          <span>智能监控平台</span>
        </div>
      </div>

      <nav class="nav-groups" aria-label="主要导航">
        <section
          v-for="group in navGroups"
          :key="group.id"
          class="nav-group"
          :aria-labelledby="`nav-group-${group.id}`"
        >
          <h2 :id="`nav-group-${group.id}`" class="nav-group-title">{{ group.label }}</h2>
          <div class="nav-list">
            <RouterLink
              v-for="item in group.items"
              :key="item.path"
              :to="item.path"
              class="nav-link"
              :data-nav-index="item.index"
            >
              <svg
                class="nav-icon"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linecap="square"
                stroke-linejoin="miter"
                aria-hidden="true"
                focusable="false"
              >
                <use :href="`#app-icon-${item.icon}`" />
              </svg>
              <span class="nav-label">{{ item.label }}</span>
              <span class="nav-index" aria-hidden="true">{{ item.index }}</span>
            </RouterLink>
          </div>
        </section>
      </nav>

    </aside>

    <div class="workspace">
      <header class="topbar">
        <div
          class="page-folio"
          :aria-label="`第 ${currentPageIndex} 页：${currentPageTitle}`"
        >
          <span class="folio-label">页面</span>
          <strong class="folio-number">{{ currentPageIndex }}</strong>
        </div>

        <div class="page-identity">
          <strong class="page-title">{{ currentPageTitle }}</strong>
          <span class="site-name">{{ currentSiteName }}</span>
        </div>

        <div class="topbar-actions">
          <label class="site-select">
            <span>当前工地</span>
            <select
              :value="store.state.siteId"
              aria-label="选择当前工地"
              @change="store.commit('setSiteId', $event.target.value)"
            >
              <option v-if="store.state.sites.length === 0" value="" disabled>
                {{ siteOptionPlaceholder }}
              </option>
              <option v-for="site in store.state.sites" :key="site.id" :value="site.id">
                {{ site.name }}
              </option>
            </select>
          </label>

          <StatusBadge :label="realtime.label" :tone="realtime.tone" />

          <div class="user-block">
            <span>{{ store.state.user?.displayName || '用户信息加载中' }}</span>
            <small>{{ roleLabels[store.state.user?.role] || (loadingSession ? '权限加载中' : '未知角色') }}</small>
          </div>

          <button class="text-button logout-button" type="button" aria-label="退出当前账号" @click="logout">
            <svg
              class="button-icon"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="square"
              stroke-linejoin="miter"
              aria-hidden="true"
              focusable="false"
            >
              <use href="#app-icon-logout" />
            </svg>
            <span>退出</span>
          </button>
        </div>
      </header>

      <main id="main-content" class="page-main" tabindex="-1" :aria-busy="loadingSession">
        <div v-if="loadingSession" class="loading-bar" role="status" aria-live="polite">
          正在加载用户和工地权限
        </div>
        <RouterView v-else />
      </main>
    </div>

    <Transition name="toast">
      <div
        v-if="store.state.toast"
        class="toast"
        :class="`toast-${store.state.toast.tone}`"
        role="status"
        aria-live="polite"
      >
        {{ store.state.toast.message }}
      </div>
    </Transition>
  </div>
</template>
