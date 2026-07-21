<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useStore } from 'vuex'
import { api, formatApiError, jsonBody, toQuery } from '../api'
import PageState from '../components/PageState.vue'
import PaginationBar from '../components/PaginationBar.vue'
import StatusBadge from '../components/StatusBadge.vue'
import ZoneScene from '../components/ZoneScene.vue'
import { formatDate, statusLabels, statusTone, typeLabels } from '../format'

const store = useStore()
const zones = ref([])
const devices = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const loading = ref(true)
const error = ref('')
const showCreate = ref(false)
const showEdit = ref(false)
const editError = ref('')
const filters = reactive({ keyword: '', type: '', status: '' })
const form = reactive({ code: '', name: '', type: 'TOWER_CRANE', zoneId: null, location: '' })
const editForm = reactive({ code: '', name: '', type: 'TOWER_CRANE', zoneId: null, location: '', configJson: '{}' })
const canManage = computed(() => ['ADMIN', 'DEVICE_MANAGER'].includes(store.state.user?.role))
const selectedDevice = computed(() => devices.value.find(item => item.id === store.state.selectedDeviceId))

async function loadZones() { zones.value = await api(`/sites/${store.state.siteId}/zones`) }
async function loadDevices() {
  loading.value = true; error.value = ''
  try {
    const data = await api(`/devices${toQuery({ siteId: store.state.siteId, zoneId: store.state.selectedZoneId, ...filters, page: page.value, pageSize: pageSize.value })}`)
    devices.value = data.items; total.value = data.total
  } catch (reason) { error.value = formatApiError(reason) }
  finally { loading.value = false }
}
async function load() { try { await Promise.all([loadZones(), loadDevices()]) } catch (reason) { error.value = formatApiError(reason); loading.value = false } }
function selectZone(id) { store.commit('setZone', store.state.selectedZoneId === id ? null : id); page.value = 1; loadDevices() }
function queryDevices() { page.value = 1; loadDevices() }
function selectDevice(id) { if (store.state.selectedDeviceId !== id) showEdit.value = false; store.commit('setDevice', id) }

function openEdit() {
  if (!selectedDevice.value) return
  const config = selectedDevice.value.configJson
  Object.assign(editForm, {
    code: selectedDevice.value.code,
    name: selectedDevice.value.name,
    type: selectedDevice.value.type,
    zoneId: selectedDevice.value.zoneId,
    location: selectedDevice.value.location || '',
    configJson: typeof config === 'string' ? config : JSON.stringify(config || {}, null, 2)
  })
  editError.value = ''
  showEdit.value = true
}

async function saveDevice() {
  editError.value = ''
  try {
    const parsedConfig = JSON.parse(editForm.configJson || '{}')
    if (!parsedConfig || Array.isArray(parsedConfig) || typeof parsedConfig !== 'object') throw new Error('扩展参数必须是 JSON 对象')
    const id = selectedDevice.value.id
    await api(`/devices/${id}`, {
      method: 'PUT',
      body: jsonBody({ ...editForm, siteId: store.state.siteId, zoneId: Number(editForm.zoneId), configJson: JSON.stringify(parsedConfig) })
    })
    showEdit.value = false
    await loadDevices()
    store.commit('setDevice', id)
    store.dispatch('notify', { tone: 'info', message: '设备基础信息和扩展参数已更新' })
  } catch (reason) {
    editError.value = reason?.status ? formatApiError(reason) : reason.message
  }
}

async function toggleDevice(device) {
  await api(`/devices/${device.id}/enabled`, { method: 'PATCH', body: jsonBody({ enabled: !device.enabled }) })
  await store.dispatch('notify', { tone: 'info', message: `${device.name}启用状态已更新` })
  loadDevices()
}

async function createDevice() {
  await api('/devices', { method: 'POST', body: jsonBody({ ...form, siteId: store.state.siteId, zoneId: Number(form.zoneId), configJson: '{}' }) })
  showCreate.value = false
  Object.assign(form, { code: '', name: '', type: 'TOWER_CRANE', zoneId: null, location: '' })
  await Promise.all([loadZones(), loadDevices()])
  store.dispatch('notify', { tone: 'info', message: '设备已新增，初始连接状态为离线' })
}

onMounted(load)
watch(() => store.state.siteId, () => { page.value = 1; store.commit('setZone', null); load() })
</script>

<template>
  <section>
    <header class="page-heading">
      <div><div class="folio"><svg viewBox="0 0 24 24"><use href="#app-icon-devices" /></svg></div><h1>设备总览</h1><p>区域场景、设备筛选和详情共享同一选择上下文；点击区域后，列表与数量会同步更新。</p></div>
      <div class="heading-meta"><div><span>设备结果</span><strong>{{ total }} 台</strong></div><div><span>选中区域</span><strong>{{ zones.find(z => z.id === store.state.selectedZoneId)?.name || '全部区域' }}</strong></div></div>
    </header>

    <div class="device-layout">
      <section class="panel scene-panel"><div class="panel-header"><div><h2>工地区域场景</h2><p>选择区域以筛选设备清单</p></div><button v-if="store.state.selectedZoneId" class="button button-secondary button-small" @click="selectZone(store.state.selectedZoneId)">查看全部区域</button></div><ZoneScene :zones="zones" :selected-id="store.state.selectedZoneId" @select="selectZone" /></section>
      <aside class="panel selection-panel"><div class="panel-header"><div><h2>设备详情</h2><p>选择列表中的设备</p></div></div><div v-if="selectedDevice" class="device-detail"><template v-if="!showEdit"><div class="detail-code">{{ selectedDevice.code }}</div><h3>{{ selectedDevice.name }}</h3><StatusBadge :label="statusLabels[selectedDevice.connectionStatus] || '未知状态'" :tone="statusTone(selectedDevice.connectionStatus)" /><dl><div><dt>设备类型</dt><dd>{{ typeLabels[selectedDevice.type] || '未知设备类型' }}</dd></div><div><dt>所属区域</dt><dd>{{ selectedDevice.zoneName }}</dd></div><div><dt>安装位置</dt><dd>{{ selectedDevice.location || '未填写' }}</dd></div><div><dt>最近上报</dt><dd>{{ formatDate(selectedDevice.lastReportedAt) }}</dd></div><div><dt>启用状态</dt><dd>{{ selectedDevice.enabled ? '启用' : '停用' }}</dd></div></dl><div v-if="canManage" class="table-actions"><button class="button button-primary" @click="openEdit">编辑设备</button><button class="button button-secondary" @click="toggleDevice(selectedDevice)">{{ selectedDevice.enabled ? '停用设备' : '启用设备' }}</button></div></template><form v-else class="device-edit-form" @submit.prevent="saveDevice"><label class="field"><span>设备编号</span><input v-model.trim="editForm.code" required /></label><label class="field"><span>设备名称</span><input v-model.trim="editForm.name" required /></label><label class="field"><span>类型</span><select v-model="editForm.type"><option v-for="(label, code) in typeLabels" :key="code" :value="code">{{ label }}</option></select></label><label class="field"><span>所属区域</span><select v-model="editForm.zoneId" required><option v-for="zone in zones" :key="zone.id" :value="zone.id">{{ zone.name }}</option></select></label><label class="field"><span>安装位置</span><input v-model.trim="editForm.location" /></label><label class="field"><span>扩展参数（JSON）</span><textarea v-model="editForm.configJson" rows="5" spellcheck="false"></textarea></label><p v-if="editError" class="form-error" role="alert">{{ editError }}</p><div class="table-actions"><button class="button button-primary" type="submit">保存修改</button><button class="button button-secondary" type="button" @click="showEdit = false">取消</button></div></form></div><PageState v-else title="尚未选择设备" message="从下方设备列表选择一项，可查看归属、位置和通信状态。" /></aside>
    </div>

    <section class="panel device-table-panel">
      <div class="panel-header"><div><h2>设备清单</h2><p>启用状态与连接状态分别维护</p></div><button v-if="canManage" class="button button-primary button-small" @click="showCreate = !showCreate">{{ showCreate ? '取消新增' : '新增设备' }}</button></div>
      <form v-if="showCreate" class="inline-form" @submit.prevent="createDevice"><label class="field"><span>设备编号</span><input v-model.trim="form.code" required /></label><label class="field"><span>设备名称</span><input v-model.trim="form.name" required /></label><label class="field"><span>类型</span><select v-model="form.type"><option v-for="(label, code) in typeLabels" :key="code" :value="code">{{ label }}</option></select></label><label class="field"><span>区域</span><select v-model="form.zoneId" required><option :value="null" disabled>请选择</option><option v-for="zone in zones" :key="zone.id" :value="zone.id">{{ zone.name }}</option></select></label><label class="field"><span>位置</span><input v-model.trim="form.location" /></label><button class="button button-primary" type="submit">保存设备</button></form>
      <div class="filter-bar"><label class="field"><span>关键字</span><input v-model="filters.keyword" placeholder="编号或名称" @keyup.enter="queryDevices" /></label><label class="field"><span>设备类型</span><select v-model="filters.type"><option value="">全部类型</option><option v-for="(label, code) in typeLabels" :key="code" :value="code">{{ label }}</option></select></label><label class="field"><span>连接状态</span><select v-model="filters.status"><option value="">全部状态</option><option value="ONLINE">在线</option><option value="OFFLINE">离线</option></select></label><button class="button button-secondary" @click="queryDevices">查询</button></div>
      <div v-if="loading" class="loading-bar">正在加载设备清单</div>
      <PageState v-else-if="error" title="设备数据加载失败" :message="error" action="重试" @action="loadDevices" />
      <div v-else class="data-table-wrap"><table class="data-table"><caption class="sr-only">设备清单</caption><thead><tr><th>设备</th><th>类型</th><th>区域</th><th>连接状态</th><th>启用</th><th>最近上报</th></tr></thead><tbody><tr v-for="device in devices" :key="device.id" :class="{ selected: store.state.selectedDeviceId === device.id }" :aria-selected="store.state.selectedDeviceId === device.id" tabindex="0" @click="selectDevice(device.id)" @keydown.enter="selectDevice(device.id)" @keydown.space.prevent="selectDevice(device.id)"><td><span class="cell-primary">{{ device.name }}</span><span class="cell-secondary">{{ device.code }}</span></td><td>{{ typeLabels[device.type] || '未知设备类型' }}</td><td>{{ device.zoneName }}</td><td><StatusBadge :label="statusLabels[device.connectionStatus] || '未知状态'" :tone="statusTone(device.connectionStatus)" /></td><td>{{ device.enabled ? '启用' : '停用' }}</td><td class="tabular">{{ formatDate(device.lastReportedAt) }}</td></tr></tbody></table><PageState v-if="!devices.length" title="暂无设备" message="当前筛选条件下没有设备记录。" /><PaginationBar v-if="total" v-model:page="page" v-model:page-size="pageSize" :total="total" aria-label="设备清单分页" @change="loadDevices" /></div>
    </section>
  </section>
</template>
