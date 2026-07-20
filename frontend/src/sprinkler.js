export function buildSprinklerAck(success, failureReason = '') {
  if (success) return { success: true }
  const normalized = String(failureReason || '').trim()
  if (!normalized) throw new Error('失败回执必须填写失败原因')
  if (normalized.length > 500) throw new Error('失败原因不能超过 500 个字符')
  return { success: false, failureReason: normalized }
}
