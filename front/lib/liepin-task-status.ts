export interface LiepinTaskStatus {
  isRunning: boolean
  starting: boolean
  paused: boolean
  pauseReason: string
  lastMessage: string
}

export function parseLiepinTaskStatus(value: unknown): LiepinTaskStatus {
  if (!value || typeof value !== 'object') throw new Error('任务状态格式无效')
  const data = value as Record<string, unknown>
  if (data.success !== true || typeof data.isRunning !== 'boolean' || typeof data.paused !== 'boolean'
    || (data.starting !== undefined && typeof data.starting !== 'boolean')) {
    throw new Error('任务状态格式无效')
  }
  return {
    isRunning: data.isRunning,
    starting: data.starting === true,
    paused: data.paused,
    pauseReason: typeof data.pauseReason === 'string' ? data.pauseReason.trim() : '',
    lastMessage: typeof data.lastMessage === 'string' ? data.lastMessage.trim() : '',
  }
}

export function liepinTaskStatusLabel(status: LiepinTaskStatus | null, unavailable: boolean): string {
  if (unavailable || !status) return '状态未知'
  if (status.paused) return '已暂停'
  if (status.starting) return '启动中'
  if (status.isRunning) return '运行中'
  return '未运行'
}
