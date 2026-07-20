const API_BASE = import.meta.env?.VITE_API_BASE || '/api'

let unauthorizedHandler = null

export class ApiError extends Error {
  constructor(message, code, status, traceId) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.traceId = traceId
  }
}

export function configureUnauthorizedHandler(handler) {
  unauthorizedHandler = typeof handler === 'function' ? handler : null
}

export function formatApiError(reason, fallback = '请求失败') {
  const message = reason?.message || fallback
  return reason?.traceId ? `${message}（追踪编号：${reason.traceId}）` : message
}

export async function api(path, options = {}) {
  const token = localStorage.getItem('sitesafe.token')
  const headers = { Accept: 'application/json', ...(options.headers || {}) }
  if (options.body && !(options.body instanceof FormData)) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = `Bearer ${token}`
  const response = await fetch(`${API_BASE}${path}`, { ...options, headers })
  const payload = await response.json().catch(() => null)
  const traceId = response.headers.get('X-Trace-Id') || payload?.traceId || null
  if (!response.ok || !payload?.success) {
    if (response.status === 401) {
      localStorage.removeItem('sitesafe.token')
      localStorage.removeItem('sitesafe.user')
      unauthorizedHandler?.()
    }
    throw new ApiError(payload?.message || '请求失败', payload?.code || 'HTTP_ERROR', response.status, traceId)
  }
  return payload.data
}

export async function apiBlob(path, options = {}) {
  const token = localStorage.getItem('sitesafe.token')
  const headers = { Accept: 'text/csv, application/octet-stream', ...(options.headers || {}) }
  if (token) headers.Authorization = `Bearer ${token}`
  const response = await fetch(`${API_BASE}${path}`, { ...options, headers })
  const traceId = response.headers.get('X-Trace-Id') || null
  if (!response.ok) {
    const payload = await response.json().catch(() => null)
    if (response.status === 401) {
      localStorage.removeItem('sitesafe.token')
      localStorage.removeItem('sitesafe.user')
      unauthorizedHandler?.()
    }
    throw new ApiError(payload?.message || '下载失败', payload?.code || 'HTTP_ERROR', response.status, traceId || payload?.traceId || null)
  }
  const disposition = response.headers.get('Content-Disposition') || ''
  const match = disposition.match(/filename="?([^";]+)"?/i)
  return { blob: await response.blob(), filename: match?.[1] || 'export.csv' }
}

export function jsonBody(value) {
  return JSON.stringify(value)
}

export function toQuery(params) {
  const query = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') query.set(key, value)
  })
  const text = query.toString()
  return text ? `?${text}` : ''
}
