import test from 'node:test'
import assert from 'node:assert/strict'
import { api, apiBlob, ApiError, configureUnauthorizedHandler, formatApiError } from '../src/api.js'

function createStorage(values = {}) {
  const data = new Map(Object.entries(values))
  return {
    getItem: key => data.get(key) ?? null,
    setItem: (key, value) => data.set(key, String(value)),
    removeItem: key => data.delete(key)
  }
}

test('API errors expose the backend trace id in user-facing text', () => {
  const error = new ApiError('服务处理失败', 'INTERNAL_ERROR', 500, 'trace-123')
  assert.equal(formatApiError(error), '服务处理失败（追踪编号：trace-123）')
  assert.equal(formatApiError(new Error('网络不可用')), '网络不可用')
})

test('API errors fall back to the response payload trace id', async t => {
  const previousStorage = globalThis.localStorage
  const previousFetch = globalThis.fetch
  globalThis.localStorage = createStorage()
  globalThis.fetch = async () => ({
    ok: false,
    status: 500,
    headers: { get: () => null },
    json: async () => ({ success: false, code: 'INTERNAL_ERROR', message: '服务处理失败', traceId: 'payload-trace' })
  })
  t.after(() => {
    globalThis.localStorage = previousStorage
    globalThis.fetch = previousFetch
  })

  await assert.rejects(api('/broken'), error => {
    assert.equal(error.traceId, 'payload-trace')
    assert.equal(formatApiError(error), '服务处理失败（追踪编号：payload-trace）')
    return true
  })
})

test('401 clears persisted credentials and invokes the global session handler before rejection', async t => {
  const previousStorage = globalThis.localStorage
  const previousFetch = globalThis.fetch
  const calls = []
  globalThis.localStorage = createStorage({ 'sitesafe.token': 'expired', 'sitesafe.user': '{}' })
  globalThis.fetch = async () => ({
    ok: false,
    status: 401,
    headers: { get: name => name === 'X-Trace-Id' ? 'trace-401' : null },
    json: async () => ({ success: false, code: 'UNAUTHORIZED', message: '登录已失效' })
  })
  configureUnauthorizedHandler(() => calls.push('session-cleared'))
  t.after(() => {
    configureUnauthorizedHandler(null)
    globalThis.localStorage = previousStorage
    globalThis.fetch = previousFetch
  })

  await assert.rejects(api('/private'), error => {
    assert.equal(error.status, 401)
    assert.equal(error.traceId, 'trace-401')
    assert.deepEqual(calls, ['session-cleared'])
    assert.equal(globalThis.localStorage.getItem('sitesafe.token'), null)
    assert.equal(globalThis.localStorage.getItem('sitesafe.user'), null)
    return true
  })
})

test('binary API responses preserve the server export filename', async t => {
  const previousStorage = globalThis.localStorage
  const previousFetch = globalThis.fetch
  globalThis.localStorage = createStorage({ 'sitesafe.token': 'valid' })
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    headers: { get: name => name === 'Content-Disposition' ? 'attachment; filename=alarms-site-1.csv' : null },
    blob: async () => new Blob(['code,title\nA-1,测试'])
  })
  t.after(() => {
    globalThis.localStorage = previousStorage
    globalThis.fetch = previousFetch
  })

  const result = await apiBlob('/alarms/export?siteId=1')
  assert.equal(result.filename, 'alarms-site-1.csv')
  assert.equal(await result.blob.text(), 'code,title\nA-1,测试')
})
