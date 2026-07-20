import { createRouter, createWebHistory } from 'vue-router'
import AppShell from './components/AppShell.vue'
import LoginView from './views/LoginView.vue'

const routes = [
  { path: '/login', component: LoginView, meta: { public: true } },
  {
    path: '/',
    component: AppShell,
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', component: () => import('./views/DashboardView.vue'), meta: { title: '综合首页' } },
      { path: 'devices', component: () => import('./views/DevicesView.vue'), meta: { title: '设备总览' } },
      { path: 'tower', component: () => import('./views/TowerView.vue'), meta: { title: '塔吊分析' } },
      { path: 'equipment', component: () => import('./views/EquipmentView.vue'), meta: { title: '大型设备监测' } },
      { path: 'environment', component: () => import('./views/EnvironmentView.vue'), meta: { title: '环境分析' } },
      { path: 'video', component: () => import('./views/VideoView.vue'), meta: { title: '视频监控' } },
      { path: 'risks', component: () => import('./views/RisksView.vue'), meta: { title: 'AI 风险' } },
      { path: 'agent', component: () => import('./views/AgentView.vue'), meta: { title: 'AI Agent 问答' } },
      { path: 'alarms', component: () => import('./views/AlarmsView.vue'), meta: { title: '告警中心' } },
      { path: 'rules', component: () => import('./views/RulesView.vue'), meta: { title: '规则配置', roles: ['ADMIN', 'SUPERVISOR'] } },
      { path: 'users', component: () => import('./views/UserView.vue'), meta: { title: '用户管理', roles: ['ADMIN'] } },
      { path: 'audit', component: () => import('./views/AuditView.vue'), meta: { title: '审计日志', roles: ['ADMIN'] } },
      { path: 'governance', component: () => import('./views/GovernanceView.vue'), meta: { title: '知识与报告', roles: ['ADMIN', 'SUPERVISOR'] } },
      { path: 'integrations', component: () => import('./views/IntegrationsView.vue'), meta: { title: '集成验收', roles: ['ADMIN', 'SUPERVISOR'] } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({ history: createWebHistory(), routes, scrollBehavior: () => ({ top: 0 }) })

router.beforeEach(to => {
  const token = localStorage.getItem('sitesafe.token')
  const user = JSON.parse(localStorage.getItem('sitesafe.user') || 'null')
  if (!to.meta.public && !token) return '/login'
  if (to.path === '/login' && token) return '/dashboard'
  if (to.meta.roles && !to.meta.roles.includes(user?.role)) return '/dashboard'
  document.title = `${to.meta.title || '登录'} · 建筑安全智能监控平台`
})

export default router
