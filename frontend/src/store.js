import { createStore } from 'vuex'
import { api, jsonBody } from './api'
import { resolveSiteId } from './session'

const storedUser = JSON.parse(localStorage.getItem('sitesafe.user') || 'null')
let realtimeSocket = null
let reconnectTimer = null

export default createStore({
  state: {
    token: localStorage.getItem('sitesafe.token') || '',
    user: storedUser,
    sites: [],
    siteId: Number(localStorage.getItem('sitesafe.siteId') || 1),
    selectedZoneId: null,
    selectedDeviceId: null,
    realtimeStatus: 'DISCONNECTED',
    latestEvent: null,
    toast: null
  },
  mutations: {
    setSession(state, { token, user }) {
      state.token = token
      state.user = user
      state.sites = []
      state.siteId = null
      state.selectedZoneId = null
      state.selectedDeviceId = null
      localStorage.removeItem('sitesafe.siteId')
      localStorage.setItem('sitesafe.token', token)
      localStorage.setItem('sitesafe.user', JSON.stringify(user))
    },
    clearSession(state) {
      state.token = ''
      state.user = null
      state.sites = []
      state.siteId = null
      state.selectedZoneId = null
      state.selectedDeviceId = null
      state.latestEvent = null
      state.realtimeStatus = 'DISCONNECTED'
      localStorage.removeItem('sitesafe.token')
      localStorage.removeItem('sitesafe.user')
      localStorage.removeItem('sitesafe.siteId')
    },
    setUser(state, user) {
      state.user = user
      localStorage.setItem('sitesafe.user', JSON.stringify(user))
    },
    setSites(state, sites) {
      state.sites = Array.isArray(sites) ? sites : []
      const siteId = resolveSiteId(state.sites, state.siteId)
      if (state.siteId !== siteId) {
        state.selectedZoneId = null
        state.selectedDeviceId = null
      }
      state.siteId = siteId
      if (siteId === null) localStorage.removeItem('sitesafe.siteId')
      else localStorage.setItem('sitesafe.siteId', String(siteId))
    },
    setSiteId(state, siteId) {
      state.siteId = resolveSiteId(state.sites, siteId)
      state.selectedZoneId = null
      state.selectedDeviceId = null
      if (state.siteId === null) localStorage.removeItem('sitesafe.siteId')
      else localStorage.setItem('sitesafe.siteId', String(state.siteId))
    },
    setZone(state, zoneId) { state.selectedZoneId = zoneId; state.selectedDeviceId = null },
    setDevice(state, deviceId) { state.selectedDeviceId = deviceId },
    setRealtimeStatus(state, status) { state.realtimeStatus = status },
    setLatestEvent(state, event) { state.latestEvent = event },
    setToast(state, toast) { state.toast = toast }
  },
  actions: {
    async login({ commit }, credentials) {
      const session = await api('/auth/login', { method: 'POST', body: jsonBody(credentials) })
      commit('setSession', { token: session.token, user: session.user })
      return session
    },
    async loadSession({ commit }) {
      const user = await api('/auth/me')
      commit('setUser', user)
      const sites = await api('/sites')
      commit('setSites', sites)
    },
    async logout({ commit, dispatch }) {
      try { await api('/auth/logout', { method: 'POST' }) } finally {
        commit('clearSession')
        dispatch('disconnectRealtime')
      }
    },
    connectRealtime({ state, commit, dispatch }) {
      if (!state.token || realtimeSocket) return
      if (reconnectTimer) {
        clearTimeout(reconnectTimer)
        reconnectTimer = null
      }
      commit('setRealtimeStatus', 'CONNECTING')
      const protocol = location.protocol === 'https:' ? 'wss' : 'ws'
      const base = import.meta.env.VITE_WS_BASE || `${protocol}://${location.host}`
      const socket = new WebSocket(`${base}/ws/events?token=${encodeURIComponent(state.token)}`)
      realtimeSocket = socket
      socket.addEventListener('open', () => commit('setRealtimeStatus', 'CONNECTED'))
      socket.addEventListener('message', event => {
        const message = JSON.parse(event.data)
        commit('setLatestEvent', message)
        if (message.type === 'alarm.created') dispatch('notify', { tone: 'danger', message: message.payload.title })
      })
      socket.addEventListener('close', () => {
        if (realtimeSocket === socket) realtimeSocket = null
        commit('setRealtimeStatus', 'DISCONNECTED')
        if (state.token) reconnectTimer = setTimeout(() => dispatch('connectRealtime'), 5000)
      })
      socket.addEventListener('error', () => socket.close())
    },
    disconnectRealtime({ commit }) {
      if (reconnectTimer) clearTimeout(reconnectTimer)
      reconnectTimer = null
      const socket = realtimeSocket
      realtimeSocket = null
      socket?.close()
      commit('setRealtimeStatus', 'DISCONNECTED')
    },
    notify({ commit }, toast) {
      commit('setToast', toast)
      setTimeout(() => commit('setToast', null), 3600)
    }
  }
})
