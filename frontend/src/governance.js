export const KNOWLEDGE_STATUSES = ['DRAFT', 'PENDING_REVIEW', 'PUBLISHED', 'REJECTED', 'ARCHIVED']
export const REPORT_STATUSES = ['DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED']

const STATUS_LABELS = {
  DRAFT: '草稿',
  PENDING_REVIEW: '待审核',
  PUBLISHED: '已发布',
  APPROVED: '已批准',
  REJECTED: '已驳回',
  ARCHIVED: '已归档',
  SENT: '已发送',
  FAILED: '发送失败'
}

export function governanceStatusLabel(status) {
  return STATUS_LABELS[status] || '未知状态'
}

export function governanceStatusTone(status) {
  if (['PUBLISHED', 'APPROVED', 'SENT'].includes(status)) return 'success'
  if (['PENDING_REVIEW'].includes(status)) return 'warning'
  if (['REJECTED', 'FAILED'].includes(status)) return 'danger'
  if (['DRAFT'].includes(status)) return 'info'
  return 'neutral'
}

export function knowledgeActions(status, isAdmin = false) {
  const actions = []
  if (['DRAFT', 'REJECTED'].includes(status)) actions.push('EDIT')
  if (status === 'DRAFT') actions.push('SUBMIT')
  if (status === 'PENDING_REVIEW' && isAdmin) actions.push('REVIEW')
  if (status === 'PUBLISHED' && isAdmin) actions.push('ARCHIVE')
  return actions
}

export function reportActions(status, isAdmin = false) {
  const actions = []
  if (['DRAFT', 'REJECTED'].includes(status)) actions.push('EDIT')
  if (status === 'DRAFT') actions.push('SUBMIT')
  if (status === 'PENDING_REVIEW' && isAdmin) actions.push('REVIEW')
  if (status === 'APPROVED') actions.push('DELIVER')
  return actions
}

export function channelReadyForDelivery(channel) {
  return Boolean(channel?.enabled && channel?.runtimeReady && channel?.credentialConfigured !== false)
}

export function reportTemplateFields() {
  return [
    'siteName', 'generatedAt', 'deviceTotal', 'onlineDeviceTotal',
    'activeAlarmTotal', 'highAlarmTotal', 'pendingRiskTotal', 'pendingSprinklerTotal'
  ]
}
