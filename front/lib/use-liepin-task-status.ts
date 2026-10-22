'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { API_BASE } from './api'
import { LiepinTaskStatus, parseLiepinTaskStatus } from './liepin-task-status'

type TaskAction = 'start' | 'stop' | 'logout'
type ActionResult = { success: boolean; message: string }
const STATUS_INTERVAL_MS = 2000
const STATUS_TIMEOUT_MS = 5000
const ACTION_TIMEOUT_MS = 15000

// Poll only the local service: this hook never opens or reads a recruitment website.
export function useLiepinTaskStatus() {
  const [status, setStatus] = useState<LiepinTaskStatus | null>(null)
  const [statusError, setStatusError] = useState('')
  const [refreshing, setRefreshing] = useState(false)
  const [pendingAction, setPendingAction] = useState<TaskAction | null>(null)
  const mounted = useRef(false)
  const generation = useRef(0)
  const activeRead = useRef<Promise<void> | null>(null)
  const readController = useRef<AbortController | null>(null)
  const actionController = useRef<AbortController | null>(null)
  // Hold admission across both the POST and its follow-up status read.
  const actionLocked = useRef(false)
  const actionInProgress = useRef(false)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const refreshAgain = useRef(false)
  const refreshRef = useRef<() => Promise<void>>(async () => {})

  const refreshStatus = useCallback((): Promise<void> => {
    if (!mounted.current || actionInProgress.current) return Promise.resolve()
    if (timer.current) clearTimeout(timer.current)
    if (activeRead.current) {
      refreshAgain.current = true
      return activeRead.current
    }
    const requestGeneration = generation.current
    const controller = new AbortController()
    readController.current = controller
    setRefreshing(true)
    const request = Promise.resolve().then(async () => {
      const timeout = setTimeout(() => controller.abort(), STATUS_TIMEOUT_MS)
      try {
        const response = await fetch(`${API_BASE}/api/liepin/status`, { signal: controller.signal, cache: 'no-store' })
        if (!response.ok) throw new Error('任务状态不可用')
        const next = parseLiepinTaskStatus(await response.json())
        if (mounted.current && requestGeneration === generation.current && !actionInProgress.current) {
          setStatus(next)
          setStatusError('')
        }
      } catch {
        if (mounted.current && requestGeneration === generation.current && !actionInProgress.current) {
          setStatusError('无法确认当前任务状态，请检查本机服务后刷新；不会因此认定任务已结束。')
        }
      } finally {
        clearTimeout(timeout)
        if (readController.current === controller) readController.current = null
        activeRead.current = null
        if (mounted.current && requestGeneration === generation.current) setRefreshing(false)
        if (mounted.current && !actionInProgress.current) {
          if (refreshAgain.current) {
            refreshAgain.current = false
            void refreshRef.current()
          } else {
            timer.current = setTimeout(() => void refreshRef.current(), STATUS_INTERVAL_MS)
          }
        }
      }
    })
    activeRead.current = request
    return request
  }, [])
  refreshRef.current = refreshStatus

  useEffect(() => {
    mounted.current = true
    void refreshStatus()
    const refreshWhenVisible = () => {
      if (document.visibilityState === 'visible') void refreshStatus()
    }
    const refreshOnFocus = () => void refreshStatus()
    document.addEventListener('visibilitychange', refreshWhenVisible)
    window.addEventListener('focus', refreshOnFocus)
    return () => {
      mounted.current = false
      generation.current++
      if (timer.current) clearTimeout(timer.current)
      refreshAgain.current = false
      readController.current?.abort()
      actionController.current?.abort()
      document.removeEventListener('visibilitychange', refreshWhenVisible)
      window.removeEventListener('focus', refreshOnFocus)
    }
  }, [refreshStatus])

  const runAction = useCallback(async (action: TaskAction): Promise<ActionResult | null> => {
    // This is called only by an explicit button click; retries and polling never submit an action.
    if (!mounted.current || actionLocked.current) return null
    actionLocked.current = true
    actionInProgress.current = true
    generation.current++
    refreshAgain.current = false
    if (timer.current) clearTimeout(timer.current)
    setPendingAction(action)
    setRefreshing(false)
    readController.current?.abort()
    await activeRead.current
    if (!mounted.current) {
      actionInProgress.current = false
      actionLocked.current = false
      return null
    }
    const controller = new AbortController()
    actionController.current = controller
    const timeout = setTimeout(() => controller.abort(), ACTION_TIMEOUT_MS)
    let result: ActionResult
    try {
      const response = await fetch(`${API_BASE}/api/liepin/${action}`, { method: 'POST', signal: controller.signal })
      const data: unknown = await response.json()
      const payload = data && typeof data === 'object' ? data as Record<string, unknown> : {}
      result = {
        success: response.ok && payload.success === true,
        message: typeof payload.message === 'string' ? payload.message : '请求结果未确认，请刷新任务状态核对，不要重复提交。',
      }
    } catch {
      result = { success: false, message: '请求结果未确认，请刷新任务状态核对，不要重复提交。' }
    } finally {
      clearTimeout(timeout)
      if (actionController.current === controller) actionController.current = null
      actionInProgress.current = false
      try {
        if (mounted.current) await refreshStatus()
      } finally {
        actionLocked.current = false
        if (mounted.current) setPendingAction(null)
      }
    }
    return mounted.current ? result : null
  }, [refreshStatus])

  return { status, statusError, refreshing, pendingAction, refreshStatus, runAction }
}
