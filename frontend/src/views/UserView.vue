<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useStore } from 'vuex'
import { api, formatApiError, jsonBody, toQuery } from '../api'
import PageState from '../components/PageState.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { formatDate, roleLabels } from '../format'

const store = useStore()
const users = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const keyword = ref('')
const loading = ref(true)
const error = ref('')
const operationError = ref('')
const showCreate = ref(false)
const submitting = ref(false)
const actionUserId = ref(null)
const resetTarget = ref(null)
const resetPassword = ref('')
const form = reactive({
  username: '',
  password: '',
  displayName: '',
  role: 'SUPERVISOR',
  siteScope: String(store.state.siteId || 1)
})

const activeOnPage = computed(() => users.value.filter(user => user.enabled).length)
const currentUserId = computed(() => Number(store.state.user?.id))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api(`/users${toQuery({ keyword: keyword.value, page: page.value, pageSize: pageSize.value })}`)
    users.value = data.items
    total.value = data.total
  } catch (reason) {
    error.value = formatApiError(reason)
  } finally {
    loading.value = false
  }
}

function queryUsers() {
  page.value = 1
  load()
}

function toggleCreate() {
  showCreate.value = !showCreate.value
  operationError.value = ''
  if (showCreate.value && !form.siteScope) form.siteScope = String(store.state.siteId || 1)
}

async function createUser() {
  submitting.value = true
  operationError.value = ''
  try {
    await api('/users', { method: 'POST', body: jsonBody({ ...form }) })
    Object.assign(form, {
      username: '',
      password: '',
      displayName: '',
      role: 'SUPERVISOR',
      siteScope: String(store.state.siteId || 1)
    })
    showCreate.value = false
    page.value = 1
    await load()
    store.dispatch('notify', { tone: 'info', message: '用户已创建' })
  } catch (reason) {
    operationError.value = formatApiError(reason)
  } finally {
    submitting.value = false
  }
}

async function toggleEnabled(user) {
  if (Number(user.id) === currentUserId.value) return
  actionUserId.value = user.id
  operationError.value = ''
  try {
    await api(`/users/${user.id}/enabled`, { method: 'PATCH', body: jsonBody({ enabled: !user.enabled }) })
    await load()
    store.dispatch('notify', { tone: 'info', message: `${user.displayName}已${user.enabled ? '停用' : '启用'}` })
  } catch (reason) {
    operationError.value = formatApiError(reason)
  } finally {
    actionUserId.value = null
  }
}

function openPasswordReset(user) {
  resetTarget.value = user
  resetPassword.value = ''
  operationError.value = ''
}

function closePasswordReset() {
  resetTarget.value = null
  resetPassword.value = ''
}

async function submitPasswordReset() {
  if (!resetTarget.value) return
  actionUserId.value = resetTarget.value.id
  operationError.value = ''
  try {
    await api(`/users/${resetTarget.value.id}/reset-password`, {
      method: 'POST',
      body: jsonBody({ password: resetPassword.value })
    })
    const displayName = resetTarget.value.displayName
    closePasswordReset()
    store.dispatch('notify', { tone: 'info', message: `${displayName}的密码已重置` })
  } catch (reason) {
    operationError.value = formatApiError(reason)
  } finally {
    actionUserId.value = null
  }
}

function siteScopeLabel(siteScope) {
  if (!siteScope) return '未分配'
  const names = String(siteScope).split(',').map(id => store.state.sites.find(site => String(site.id) === id.trim())?.name).filter(Boolean)
  return names.length ? names.join('、') : String(siteScope)
}

onMounted(load)
</script>

<template>
  <section>
    <header class="page-heading">
      <div>
        <div class="folio"><svg viewBox="0 0 24 24"><use href="#app-icon-users" /></svg></div>
        <h1>用户管理</h1>
        <p>维护平台账号、角色和工地范围。用户停用后无法登录；密码重置不会在页面中回显原密码。</p>
      </div>
      <div class="heading-meta">
        <div><span>用户总数</span><strong>{{ total }} 人</strong></div>
        <div><span>本页启用</span><strong>{{ activeOnPage }} 人</strong></div>
      </div>
    </header>

    <section class="panel user-panel" :aria-busy="loading">
      <div class="panel-header">
        <div><h2>平台账号</h2><p>仅系统管理员可访问和操作</p></div>
        <button class="button button-primary button-small" type="button" @click="toggleCreate">{{ showCreate ? '取消新增' : '新增用户' }}</button>
      </div>

      <form v-if="showCreate" class="inline-form user-create-form" @submit.prevent="createUser">
        <label class="field"><span>用户名</span><input v-model.trim="form.username" autocomplete="username" required /></label>
        <label class="field"><span>姓名</span><input v-model.trim="form.displayName" autocomplete="name" required /></label>
        <label class="field"><span>初始密码</span><input v-model="form.password" type="password" autocomplete="new-password" minlength="8" maxlength="72" required /></label>
        <label class="field"><span>角色</span><select v-model="form.role"><option v-for="(label, code) in roleLabels" :key="code" :value="code">{{ label }}</option></select></label>
        <label class="field"><span>工地范围</span><select v-model="form.siteScope" required><option v-for="site in store.state.sites" :key="site.id" :value="String(site.id)">{{ site.name }}</option></select></label>
        <button class="button button-primary" type="submit" :disabled="submitting">{{ submitting ? '正在保存' : '保存用户' }}</button>
      </form>

      <form v-if="resetTarget" class="password-reset-panel" aria-labelledby="password-reset-title" @submit.prevent="submitPasswordReset">
        <div><h3 id="password-reset-title">重置 {{ resetTarget.displayName }} 的密码</h3><p>请输入 8 至 72 位的新密码。</p></div>
        <label class="field"><span>新密码</span><input v-model="resetPassword" type="password" autocomplete="new-password" minlength="8" maxlength="72" required /></label>
        <div class="password-reset-actions"><button class="button button-primary" type="submit" :disabled="actionUserId === resetTarget.id">确认重置</button><button class="button button-secondary" type="button" @click="closePasswordReset">取消</button></div>
      </form>

      <p v-if="operationError" class="form-error user-operation-error" role="alert">{{ operationError }}</p>

      <form class="filter-bar" role="search" @submit.prevent="queryUsers">
        <label class="field user-search-field"><span>关键字</span><input v-model.trim="keyword" type="search" placeholder="用户名或姓名" /></label>
        <button class="button button-secondary" type="submit">查询</button>
      </form>

      <div v-if="loading" class="loading-bar" role="status">正在加载用户列表</div>
      <PageState v-else-if="error" title="用户列表加载失败" :message="error" action="重试" @action="load" />
      <div v-else class="data-table-wrap">
        <table class="data-table user-table">
          <caption class="sr-only">平台用户列表</caption>
          <thead><tr><th>用户</th><th>角色</th><th>工地范围</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="user in users" :key="user.id">
              <td><span class="cell-primary">{{ user.displayName }}</span><span class="cell-secondary">{{ user.username }}<template v-if="Number(user.id) === currentUserId"> · 当前账号</template></span></td>
              <td>{{ roleLabels[user.role] || '未知角色' }}</td>
              <td>{{ siteScopeLabel(user.siteScope) }}</td>
              <td><StatusBadge :label="user.enabled ? '启用' : '停用'" :tone="user.enabled ? 'success' : 'danger'" /></td>
              <td class="tabular">{{ formatDate(user.createdAt) }}</td>
              <td><div class="table-actions"><button class="button button-secondary button-small" type="button" :disabled="actionUserId === user.id || Number(user.id) === currentUserId" :title="Number(user.id) === currentUserId ? '不能停用当前登录账号' : undefined" @click="toggleEnabled(user)">{{ user.enabled ? '停用' : '启用' }}</button><button class="button button-secondary button-small" type="button" :disabled="actionUserId === user.id" @click="openPasswordReset(user)">重置密码</button></div></td>
            </tr>
          </tbody>
        </table>
        <PageState v-if="!users.length" title="暂无用户" message="当前关键字下没有匹配的用户记录。" />
        <PaginationBar v-if="total" v-model:page="page" v-model:page-size="pageSize" :total="total" aria-label="用户列表分页" @change="load" />
      </div>
    </section>
  </section>
</template>
