import test from 'node:test'
import assert from 'node:assert/strict'
import { clampPage, collectAllPages, getPageCount, getVisiblePages } from '../src/pagination.js'

test('page count always exposes a valid first page', () => {
  assert.equal(getPageCount(0, 10), 1)
  assert.equal(getPageCount(101, 20), 6)
  assert.equal(getPageCount(10, 0), 1)
  assert.equal(getPageCount(Number.POSITIVE_INFINITY, 10), 1)
})

test('page values are clamped to the available range', () => {
  assert.equal(clampPage(-2, 8), 1)
  assert.equal(clampPage(4, 8), 4)
  assert.equal(clampPage(20, 8), 8)
})

test('visible pages stay centered and inside the available range', () => {
  assert.deepEqual(getVisiblePages(1, 12), [1, 2, 3, 4, 5])
  assert.deepEqual(getVisiblePages(6, 12), [4, 5, 6, 7, 8])
  assert.deepEqual(getVisiblePages(12, 12), [8, 9, 10, 11, 12])
  assert.deepEqual(getVisiblePages(3, 3), [1, 2, 3])
})

test('all paged records are collected without a first-page cap', async () => {
  const records = Array.from({ length: 205 }, (_, index) => ({ id: index + 1 }))
  const requestedPages = []
  const result = await collectAllPages(async (page, pageSize) => {
    requestedPages.push(page)
    const offset = (page - 1) * pageSize
    return { items: records.slice(offset, offset + pageSize), total: records.length }
  })

  assert.equal(result.length, 205)
  assert.deepEqual(requestedPages, [1, 2, 3])
  assert.equal(result.at(-1).id, 205)
})
