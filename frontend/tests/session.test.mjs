import test from 'node:test'
import assert from 'node:assert/strict'
import { createUnauthorizedHandler, resolveSiteId } from '../src/session.js'

test('site selection keeps an allowed site and falls back for a different account', () => {
  const sites = [{ id: 7 }, { id: 9 }]
  assert.equal(resolveSiteId(sites, 9), 9)
  assert.equal(resolveSiteId(sites, 1), 7)
  assert.equal(resolveSiteId([], 1), null)
})

test('unauthorized handler synchronously clears Vuex state and redirects to login', () => {
  const calls = []
  const store = {
    commit: mutation => calls.push(['commit', mutation]),
    dispatch: action => calls.push(['dispatch', action])
  }
  const router = {
    currentRoute: { value: { path: '/tower', fullPath: '/tower?device=1' } },
    replace: target => calls.push(['replace', target])
  }

  createUnauthorizedHandler(store, router)()

  assert.deepEqual(calls, [
    ['commit', 'clearSession'],
    ['dispatch', 'disconnectRealtime'],
    ['replace', { path: '/login', query: { redirect: '/tower?device=1' } }]
  ])
})
