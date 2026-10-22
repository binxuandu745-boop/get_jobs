'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { apiUrl } from '@/lib/api'

const MODE_LABELS = {
  boss: 'BOSS直聘',
  liepin: '猎聘',
  '51job': '51job',
  zhilian: '智联',
} as const

type PlatformMode = keyof typeof MODE_LABELS

type BrowserStatus = {
  platformMode: PlatformMode | 'all'
  initialized: boolean
  switchingMode: boolean
  canSwitchMode: boolean
  modeMessage?: string
}

type Notice = { kind: 'info' | 'success' | 'error'; text: string }

function isSinglePlatformMode(value: unknown): value is PlatformMode {
  return value === 'boss' || value === 'liepin' || value === '51job' || value === 'zhilian'
}

function parseStatus(data: unknown): BrowserStatus | null {
  if (!data || typeof data !== 'object') return null
  const value = data as Record<string, unknown>
  if (
    (!isSinglePlatformMode(value.platformMode) && value.platformMode !== 'all') ||
    typeof value.initialized !== 'boolean' ||
    typeof value.switchingMode !== 'boolean' ||
    typeof value.canSwitchMode !== 'boolean'
  ) return null

  return {
    platformMode: value.platformMode,
    initialized: value.initialized,
    switchingMode: value.switchingMode,
    canSwitchMode: value.canSwitchMode,
    modeMessage: typeof value.modeMessage === 'string' ? value.modeMessage : undefined,
  }
}

export default function BrowserModeSelector() {
  const [status, setStatus] = useState<BrowserStatus | null>(null)
  const [selectedMode, setSelectedMode] = useState<PlatformMode | ''>('')
  const [confirmMode, setConfirmMode] = useState<PlatformMode | null>(null)
  const [checking, setChecking] = useState(false)
  const [switching, setSwitching] = useState(false)
  const [statusError, setStatusError] = useState('')
  const [notice, setNotice] = useState<Notice | null>(null)
  const mountedRef = useRef(false)
  const statusRef = useRef<BrowserStatus | null>(null)
  const userDirtyRef = useRef(false)
  const confirmModeRef = useRef<PlatformMode | null>(null)
  const completedSwitchRef = useRef<{ mode: PlatformMode; message: string } | null>(null)
  const statusControllerRef = useRef<AbortController | null>(null)
  const switchControllerRef = useRef<AbortController | null>(null)
  const switchingRef = useRef(false)

  const applyStatus = useCallback((nextStatus: BrowserStatus) => {
    statusRef.current = nextStatus
    setStatus(nextStatus)
    if (!userDirtyRef.current && !confirmModeRef.current) {
      setSelectedMode(nextStatus.platformMode === 'all' ? '' : nextStatus.platformMode)
    }

    const completedSwitch = completedSwitchRef.current
    if (!completedSwitch) return
    if (nextStatus.switchingMode) {
      setNotice({ kind: 'info', text: '后台正在切换浏览器，请等待状态确认。' })
    } else if (nextStatus.platformMode !== completedSwitch.mode) {
      completedSwitchRef.current = null
      setNotice({ kind: 'info', text: '当前浏览器平台已变化，请以最新状态为准。' })
    } else if (!nextStatus.initialized) {
      completedSwitchRef.current = null
      setNotice({ kind: 'error', text: '当前平台已保存，但浏览器未就绪。请核对状态后重新确认切换。' })
    } else {
      setNotice({ kind: 'success', text: completedSwitch.message })
    }
  }, [])

  const readStatus = useCallback(async () => {
    if (!mountedRef.current || switchingRef.current || statusControllerRef.current) return
    const controller = new AbortController()
    statusControllerRef.current = controller
    setChecking(true)
    const timeout = setTimeout(() => controller.abort(), 5000)
    const isCurrentRequest = () => mountedRef.current && statusControllerRef.current === controller

    try {
      const response = await fetch(apiUrl('/api/playwright/status'), {
        cache: 'no-store',
        signal: controller.signal,
      })
      if (!response.ok) throw new Error('Status unavailable')
      const nextStatus = parseStatus(await response.json())
      if (!nextStatus) throw new Error('Invalid status')
      if (!isCurrentRequest()) return
      applyStatus(nextStatus)
      setStatusError('')
    } catch {
      if (!isCurrentRequest()) return
      statusRef.current = null
      setStatus(null)
      setStatusError('无法读取浏览器状态，请刷新状态。')
      if (completedSwitchRef.current) {
        setNotice({ kind: 'info', text: '尚无法确认浏览器状态，请刷新状态核对切换结果。' })
      }
    } finally {
      clearTimeout(timeout)
      if (isCurrentRequest()) {
        statusControllerRef.current = null
        setChecking(false)
      }
    }
  }, [applyStatus])

  useEffect(() => {
    mountedRef.current = true
    void readStatus()
    const interval = setInterval(() => void readStatus(), 20000)
    return () => {
      mountedRef.current = false
      clearInterval(interval)
      statusControllerRef.current?.abort()
      statusControllerRef.current = null
      switchControllerRef.current?.abort()
      switchControllerRef.current = null
      switchingRef.current = false
    }
  }, [readStatus])

  const switchMode = async (mode: PlatformMode) => {
    const currentStatus = statusRef.current
    if (
      !mountedRef.current || switchingRef.current || !currentStatus ||
      confirmModeRef.current !== mode ||
      currentStatus.switchingMode || !currentStatus.canSwitchMode ||
      (currentStatus.platformMode === mode && currentStatus.initialized)
    ) return

    switchingRef.current = true
    completedSwitchRef.current = null
    setSwitching(true)
    confirmModeRef.current = null
    setConfirmMode(null)
    setNotice({ kind: 'info', text: '正在重新打开求职助手浏览器，请稍候（最多约 3 分钟）。' })
    statusControllerRef.current?.abort()
    statusControllerRef.current = null
    setChecking(false)

    const controller = new AbortController()
    switchControllerRef.current = controller
    let timedOut = false
    const timeout = setTimeout(() => {
      timedOut = true
      controller.abort()
    }, 180000)
    const isCurrentRequest = () => mountedRef.current && switchControllerRef.current === controller

    try {
      const response = await fetch(apiUrl('/api/playwright/platform-mode'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mode }),
        signal: controller.signal,
      })
      const data: unknown = await response.json()
      if (!isCurrentRequest()) return
      const result = data && typeof data === 'object' ? data as Record<string, unknown> : null
      const message = typeof result?.message === 'string' ? result.message : ''
      if (!response.ok || result?.success !== true) {
        const defaultMessage = response.status === 409
          ? '浏览器当前忙碌，请等待任务结束后再切换。'
          : response.status === 400
          ? '浏览器模式无效，请刷新状态。'
          : '浏览器切换失败，请刷新状态后确认。'
        setNotice({ kind: 'error', text: message || defaultMessage })
      } else {
        const returnedStatus = parseStatus(data)
        if (!returnedStatus || returnedStatus.platformMode !== mode) {
          setNotice({ kind: 'error', text: '返回的平台状态与选择不一致，请刷新状态核对切换结果。' })
        } else if (!returnedStatus.initialized || returnedStatus.switchingMode) {
          setNotice({ kind: 'error', text: '平台选择已保存，但浏览器尚未就绪。请刷新状态核对后再确认重试。' })
        } else {
          completedSwitchRef.current = { mode, message: message || `已切换为${MODE_LABELS[mode]}。` }
          setNotice({ kind: 'info', text: '切换请求已完成，正在核对浏览器状态。' })
        }
      }
    } catch {
      if (!isCurrentRequest()) return
      setNotice({
        kind: 'error',
        text: timedOut
          ? '切换请求已超时，后台可能仍在处理。请刷新状态确认；不会自动重试。'
          : '无法确认切换结果，后台可能仍在处理。请刷新状态确认；不会自动重试。',
      })
    } finally {
      clearTimeout(timeout)
      if (isCurrentRequest()) {
        switchControllerRef.current = null
        switchingRef.current = false
        setSwitching(false)
        statusRef.current = null
        setStatus(null)
        await readStatus()
      }
    }
  }

  const controlsDisabled = !status || checking || switching || status.switchingMode || !status.canSwitchMode
  const sameInitializedMode = !!status && selectedMode === status.platformMode && status.initialized
  const switchDisabled = controlsDisabled || selectedMode === '' || sameInitializedMode
  const confirmDisabled = controlsDisabled || (!!status && confirmMode === status.platformMode && status.initialized)

  return (
    <section aria-label="求职助手浏览器模式" className="rounded-xl border border-white/20 bg-white/10 p-3 text-white dark:border-strokedark dark:bg-white/5">
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm font-medium">浏览器平台</span>
        <button
          type="button"
          onClick={() => void readStatus()}
          disabled={checking || switching}
          className="text-xs text-cyan-100 underline-offset-2 hover:underline disabled:cursor-wait disabled:opacity-50"
        >
          {checking ? '读取中…' : '刷新状态'}
        </button>
      </div>
      <p id="browser-platform-mode-current" className="mt-2 break-words text-xs text-white/80" aria-live="polite">
        当前：{status ? status.platformMode === 'all' ? '旧配置全部平台，请选择一个' : MODE_LABELS[status.platformMode] : '未知'}
        {status && ` · ${switching || status.switchingMode ? '切换中' : status.initialized ? '已就绪' : '浏览器未启动'}`}
      </p>
      <fieldset disabled={controlsDisabled} aria-describedby="browser-platform-mode-current" className="mt-2 grid grid-cols-2 gap-2">
        <legend className="sr-only">每次只启用一个网站</legend>
        {Object.entries(MODE_LABELS).map(([mode, label]) => (
          <label key={mode} className={`flex items-center gap-1.5 rounded-md border border-white/20 px-2 py-2 text-xs ${controlsDisabled ? 'cursor-not-allowed opacity-60' : 'cursor-pointer hover:bg-white/10'}`}>
            <input
              type="radio"
              name="browser-platform-mode"
              value={mode}
              checked={selectedMode === mode}
              onClick={() => { userDirtyRef.current = true }}
              onChange={() => {
                userDirtyRef.current = true
                setSelectedMode(mode as PlatformMode)
                confirmModeRef.current = null
                setConfirmMode(null)
              }}
              className="shrink-0 accent-cyan-300"
            />
            <span>{label}</span>
          </label>
        ))}
      </fieldset>
      <p className="mt-2 text-xs text-white/80">每次只启用一个网站。</p>
      <button
        type="button"
        disabled={switchDisabled}
        onClick={() => {
          if (selectedMode) {
            confirmModeRef.current = selectedMode
            setConfirmMode(selectedMode)
          }
        }}
        className="mt-2 w-full rounded-md bg-white/20 px-3 py-2 text-xs hover:bg-white/30 disabled:cursor-not-allowed disabled:opacity-50"
      >
        {switching || status?.switchingMode ? '切换中…' : '切换'}
      </button>
      {confirmMode && (
        <div className="mt-3 rounded-lg border border-amber-200/40 bg-black/10 p-2 text-xs">
          <p>确认切换为{MODE_LABELS[confirmMode]}？</p>
          <p className="mt-1 leading-relaxed text-white/80">仅重新打开求职助手浏览器，保留登录资料，不会开始投递。</p>
          <div className="mt-2 flex gap-2">
            <button
              type="button"
              disabled={confirmDisabled}
              onClick={() => {
                const mode = confirmModeRef.current
                if (mode) void switchMode(mode)
              }}
              className="rounded bg-amber-100 px-2 py-1.5 text-gray-900 disabled:cursor-not-allowed disabled:opacity-50"
            >确认切换</button>
            <button
              type="button"
              onClick={() => {
                confirmModeRef.current = null
                setConfirmMode(null)
              }}
              className="rounded bg-white/15 px-2 py-1.5 hover:bg-white/25"
            >取消</button>
          </div>
        </div>
      )}
      {status?.modeMessage && <p className="mt-2 break-words text-xs leading-relaxed text-white/80">{status.modeMessage}</p>}
      {statusError && <p role="status" className="mt-2 text-xs leading-relaxed text-amber-100">{statusError}</p>}
      {notice && (
        <p role="status" className={`mt-2 break-words text-xs leading-relaxed ${notice.kind === 'error' ? 'text-amber-100' : notice.kind === 'success' ? 'text-green-100' : 'text-white/80'}`}>
          {notice.text}
        </p>
      )}
    </section>
  )
}
