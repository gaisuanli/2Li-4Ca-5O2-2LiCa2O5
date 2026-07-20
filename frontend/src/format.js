export const typeLabels = {
  TOWER_CRANE: '塔吊', ELEVATOR: '施工升降机', FORMWORK: '高支模', FOUNDATION_PIT: '深基坑',
  ENVIRONMENT: '环境监测', SPRINKLER: '喷淋设备', CAMERA: '摄像头'
}

export const roleLabels = {
  ADMIN: '系统管理员',
  SUPERVISOR: '项目主管',
  DEVICE_MANAGER: '设备管理员'
}

export const statusLabels = {
  ONLINE: '在线', OFFLINE: '离线', PENDING: '待确认', PROCESSING: '处理中', RESOLVED: '已解决', CLOSED: '已关闭',
  PENDING_REVIEW: '待复核', CONFIRMED: '已确认', FALSE_POSITIVE: '误报',
  CREATED: '已创建', DISPATCHED: '已下发', EXECUTED: '已执行', FAILED: '失败',
  READY: '可播放', NOT_CONFIGURED: '未配置流地址'
}

export const sourceTypeLabels = {
  DEVICE_RULE: '设备规则',
  ENVIRONMENT_RULE: '环境规则',
  AI_RISK: 'AI 风险',
  SYSTEM: '系统事件',
  SYSTEM_DEVICE_OFFLINE: '设备离线监测'
}

export const auditActionLabels = {
  TOKEN_REFRESH: '刷新登录状态', LOGOUT: '退出登录',
  DEVICE_CREATE: '新增设备', DEVICE_UPDATE: '更新设备', DEVICE_ENABLE_CHANGE: '变更设备启用状态', DEVICE_CONNECTION_CHANGE: '变更设备连接状态',
  RISK_CREATE: '新增风险事件', RISK_CONFIRM: '确认风险有效', RISK_FALSE_POSITIVE: '标记风险误报',
  ALARM_CONFIRM: '确认告警', ALARM_RESOLVE: '解决告警', ALARM_CLOSE: '关闭告警',
  SPRINKLER_TASK_CREATE: '创建喷淋任务', SPRINKLER_TASK_DISPATCH: '下发喷淋任务', SPRINKLER_TASK_ACK: '记录喷淋回执', SPRINKLER_TASK_TIMEOUT: '喷淋任务超时',
  RULE_CREATE: '新增规则', RULE_UPDATE: '更新规则', RULE_ENABLE_CHANGE: '变更规则启用状态',
  USER_CREATE: '新增用户', USER_ENABLE_CHANGE: '变更用户启用状态', USER_PASSWORD_RESET: '重置用户密码',
  AI_AGENT_CONVERSATION_CREATE: '新建 AI 会话', AI_AGENT_MESSAGE_SEND: '发送 AI 消息', AI_AGENT_MESSAGE_FAILED: 'AI 消息处理失败'
}

export const auditObjectLabels = {
  SESSION: '登录会话', DEVICE: '设备', AI_RISK: 'AI 风险', ALARM: '告警',
  SPRINKLER_TASK: '喷淋任务', ALARM_RULE: '告警规则', APP_USER: '平台用户',
  AI_AGENT_CONVERSATION: 'AI 会话'
}

const auditDetailTokenLabels = {
  ...statusLabels,
  ...roleLabels,
  OPENAI_COMPATIBLE: '兼容 API',
  DEMO: '演示模式',
  DISABLED: '未启用',
  true: '启用',
  false: '停用'
}

export function formatAuditDetail(value) {
  if (!value) return '—'
  return String(value).replace(
    /\b(OPENAI_COMPATIBLE|DEVICE_MANAGER|SUPERVISOR|ADMIN|ONLINE|OFFLINE|PENDING_REVIEW|PROCESSING|RESOLVED|CLOSED|CONFIRMED|FALSE_POSITIVE|PENDING|CREATED|DISPATCHED|EXECUTED|FAILED|READY|NOT_CONFIGURED|DEMO|DISABLED|true|false)\b/g,
    token => auditDetailTokenLabels[token] || '未识别状态'
  )
}

export function statusTone(status) {
  if (['ONLINE', 'RESOLVED', 'CLOSED', 'CONFIRMED', 'EXECUTED', 'READY'].includes(status)) return 'success'
  if (['PENDING', 'PENDING_REVIEW', 'CREATED', 'DISPATCHED', 'PROCESSING'].includes(status)) return 'warning'
  if (['OFFLINE', 'FAILED', 'HIGH'].includes(status)) return 'danger'
  if (['MEDIUM', 'FALSE_POSITIVE'].includes(status)) return 'info'
  return 'neutral'
}

export function formatDate(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(date)
}

export function metricLabel(code) {
  return ({
    windSpeed: '风速', weight: '吊重', rotation: '回转角', height: '吊钩高度',
    amplitude: '幅度', moment: '力矩', obliquity: '倾角',
    pm25: 'PM2.5', pm10: 'PM10', noise: '噪声', temperature: '温度', humidity: '湿度',
    load: '载重', floor: '当前层站', speed: '运行速度', direction: '运行方向',
    doorStatus: '门状态', limitStatus: '限位状态', axialForce: '轴力', displacement: '位移',
    settlement: '沉降', xAngle: 'X 轴倾角', yAngle: 'Y 轴倾角', pressure: '压力',
    horizontalDisplacement: '水平位移', waterLevel: '水位', earthPressure: '土压力',
    strain: '应变', settlementRate: '沉降速率'
  })[code] || '未识别指标'
}
