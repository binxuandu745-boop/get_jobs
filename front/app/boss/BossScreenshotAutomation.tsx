'use client'

import Link from 'next/link'
import { FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import { BiImage, BiSave } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { apiUrl } from '@/lib/api'
import { OFFLINE_SCREENSHOT_HEADER, ScreenshotRules, apiErrorMessage, buildScreenshotRules, defaultScreenshotRules } from '@/lib/screenshot-analysis'

type Settings = { enabled: boolean; rules: ScreenshotRules }
type SettingsResponse = { data: Settings; visionReady: boolean }

function parseSettingsResponse(payload: unknown): SettingsResponse {
  if (!payload || typeof payload !== 'object') throw new Error('无法确认截图自动筛选配置')
  const envelope = payload as Record<string, unknown>
  if (envelope.success !== true) throw new Error(apiErrorMessage(payload, '无法读取截图自动筛选配置'))
  if (!envelope.data || typeof envelope.data !== 'object' || typeof envelope.visionReady !== 'boolean') throw new Error('截图自动筛选状态不完整')
  const data = envelope.data as Record<string, unknown>
  if (typeof data.enabled !== 'boolean' || !data.rules || typeof data.rules !== 'object') throw new Error('截图自动筛选配置不完整')
  const raw = data.rules as Record<string, unknown>
  const validList = (value: unknown, allowEmpty: boolean): value is string[] => Array.isArray(value)
    && value.length <= 20 && (allowEmpty || value.length > 0)
    && value.every(item => typeof item === 'string' && item.trim().length > 0 && item.length <= 80)
  if (!validList(raw.roleTerms, false) || !validList(raw.cities, true)
    || typeof raw.requireInternship !== 'boolean'
    || typeof raw.minimumDailySalary !== 'number' || typeof raw.minimumMonthlySalary !== 'number'
    || !(raw.graduationYear === null || (typeof raw.graduationYear === 'number' && Number.isInteger(raw.graduationYear)))) {
    throw new Error('截图筛选条件格式不正确')
  }
  // Apply the same ranges as manual rule editing before trusting a server response.
  buildScreenshotRules({ roleTerms: raw.roleTerms.join(','), cities: raw.cities.join(','),
    requireInternship: raw.requireInternship, daily: String(raw.minimumDailySalary), monthly: String(raw.minimumMonthlySalary),
    graduationYear: raw.graduationYear === null ? '' : String(raw.graduationYear) })
  return { data: { enabled: data.enabled, rules: raw as unknown as ScreenshotRules }, visionReady: envelope.visionReady }
}

export default function BossScreenshotAutomation() {
  const initial = defaultScreenshotRules()
  const [enabled, setEnabled] = useState(true)
  const [roleTerms, setRoleTerms] = useState(initial.roleTerms.join(','))
  const [requireInternship, setRequireInternship] = useState(initial.requireInternship)
  const [daily, setDaily] = useState(String(initial.minimumDailySalary))
  const [monthly, setMonthly] = useState(String(initial.minimumMonthlySalary))
  const [cities, setCities] = useState(initial.cities.join(','))
  const [graduationYear, setGraduationYear] = useState(initial.graduationYear === null ? '' : String(initial.graduationYear))
  const [visionReady, setVisionReady] = useState<boolean | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const request = useRef<AbortController | null>(null)
  const busy = useRef(false)
  const mounted = useRef(false)

  const apply = useCallback((response: SettingsResponse) => {
    const rules = response.data.rules
    setEnabled(response.data.enabled); setVisionReady(response.visionReady)
    setRoleTerms(rules.roleTerms.join(',')); setRequireInternship(rules.requireInternship)
    setDaily(String(rules.minimumDailySalary)); setMonthly(String(rules.minimumMonthlySalary))
    setCities(rules.cities.join(',')); setGraduationYear(rules.graduationYear === null ? '' : String(rules.graduationYear))
  }, [])

  const load = useCallback(async () => {
    if (busy.current) return
    busy.current = true
    setLoading(true); setError(''); setNotice('')
    const controller = new AbortController()
    request.current = controller
    const timer = setTimeout(() => controller.abort(), 15_000)
    try {
      const response = await fetch(apiUrl('/api/boss/screenshot-automation'), { cache: 'no-store', signal: controller.signal })
      const payload: unknown = await response.json()
      if (!response.ok) throw new Error(apiErrorMessage(payload, '读取截图自动筛选配置失败'))
      const parsed = parseSettingsResponse(payload)
      if (mounted.current && request.current === controller) apply(parsed)
    } catch (caught) {
      if (mounted.current && request.current === controller) {
        setVisionReady(null)
        setError((controller.signal.aborted ? '读取配置超时' : caught instanceof Error ? caught.message : '读取配置失败')
          + '。尚未确认保存状态；可以重新读取，或修改下列表单后重新保存。')
      }
    } finally {
      clearTimeout(timer)
      if (request.current === controller) {
        request.current = null; busy.current = false
        if (mounted.current) setLoading(false)
      }
    }
  }, [apply])

  useEffect(() => {
    mounted.current = true
    void load()
    return () => { mounted.current = false; request.current?.abort(); request.current = null; busy.current = false }
  }, [load])

  const save = async (event: FormEvent) => {
    event.preventDefault()
    if (busy.current) return
    let rules: ScreenshotRules
    try { rules = buildScreenshotRules({ roleTerms, requireInternship, daily, monthly, cities, graduationYear }) }
    catch (caught) { setError(caught instanceof Error ? caught.message : '请检查筛选条件'); return }
    busy.current = true; setSaving(true); setError(''); setNotice('')
    const controller = new AbortController()
    request.current = controller
    const timer = setTimeout(() => controller.abort(), 15_000)
    try {
      const response = await fetch(apiUrl('/api/boss/screenshot-automation'), {
        method: 'POST', headers: { 'Content-Type': 'application/json', ...OFFLINE_SCREENSHOT_HEADER },
        body: JSON.stringify({ enabled, rules }), signal: controller.signal,
      })
      const payload: unknown = await response.json()
      if (!response.ok) throw new Error(apiErrorMessage(payload, '保存截图自动筛选配置失败'))
      const parsed = parseSettingsResponse(payload)
      if (mounted.current && request.current === controller) {
        apply(parsed)
        setNotice('截图自动筛选配置已保存，下次开始BOSS任务时使用。')
      }
    } catch (caught) {
      if (mounted.current && request.current === controller) setError(controller.signal.aborted
        ? '保存请求超时，尚不能确认是否保存成功；表单已保留，请重新读取配置核对。'
        : caught instanceof Error ? caught.message : '配置保存失败，表单已保留')
    } finally {
      clearTimeout(timer)
      if (request.current === controller) {
        request.current = null; busy.current = false
        if (mounted.current) setSaving(false)
      }
    }
  }

  const disabled = loading || saving
  const fieldClass = 'space-y-2 text-sm text-black dark:text-white'
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2"><BiImage /> 截图自动筛选</CardTitle>
        <CardDescription>自动截取当前岗位详情，视觉模型提取岗位字段，再由Java按下列规则筛选，符合后才沟通；关闭时恢复原详情数据筛选。</CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={save} className="space-y-4">
          <label className="flex items-start gap-2 text-sm text-black dark:text-white">
            <input type="checkbox" checked={enabled} onChange={event => setEnabled(event.target.checked)} disabled={disabled} className="mt-1" />
            <span>启用BOSS自动截图、上传与筛选。每个岗位的1—6张连续截图会发送到已配置的视觉模型联合识别，Java判断为符合（MATCH）后才继续沟通，可能产生API费用。</span>
          </label>
          <p className="text-sm text-muted-foreground">截图未提供的条件按符合处理；信息矛盾、无法识别或明确不符时跳过。公司、岗位及HR黑名单仍然生效。</p>
          <p className="text-sm"><Link href="/screenshot-analysis" className="text-primary underline underline-offset-2">配置截图识别模型与独立API Key</Link></p>
          {visionReady === false && <p role="status" className="rounded-lg bg-amber-50 p-3 text-sm text-amber-800 dark:bg-amber-950 dark:text-amber-200">截图识别模型或独立API Key尚未配置完成。请先完成模型配置，再开始启用了截图筛选的BOSS任务。</p>}
          <div className="grid gap-4 md:grid-cols-2">
            <label className={fieldClass}>岗位方向（逗号分隔，最多20项）<Input value={roleTerms} onChange={event => setRoleTerms(event.target.value)} disabled={disabled} maxLength={1619} required /></label>
            <label className={fieldClass}>城市（留空不限，最多20项）<Input value={cities} onChange={event => setCities(event.target.value)} disabled={disabled} maxLength={1619} /></label>
            <label className={fieldClass}>最低日薪（元/天）<Input type="number" min="0" max="1000000" step="0.01" value={daily} onChange={event => setDaily(event.target.value)} disabled={disabled} required /></label>
            <label className={fieldClass}>最低月薪（元/月）<Input type="number" min="0" max="1000000" step="0.01" value={monthly} onChange={event => setMonthly(event.target.value)} disabled={disabled} required /></label>
            <label className={fieldClass}>毕业年份（留空不筛届次）<Input type="number" min="2000" max="2100" step="1" value={graduationYear} onChange={event => setGraduationYear(event.target.value)} disabled={disabled} /></label>
            <label className="flex items-center gap-2 self-center text-sm text-black dark:text-white"><input type="checkbox" checked={requireInternship} onChange={event => setRequireInternship(event.target.checked)} disabled={disabled} /> 需要实习岗位信息</label>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <Button type="submit" disabled={disabled}><BiSave /> {saving ? '保存中…' : '保存截图筛选配置'}</Button>
            <Button type="button" variant="outline" onClick={() => void load()} disabled={disabled}>{loading ? '读取中…' : '重新读取'}</Button>
            <span className="text-xs text-muted-foreground">修改后保存，下次开始任务时生效。</span>
          </div>
          {notice && <p role="status" className="text-sm text-green-700 dark:text-green-300">{notice}</p>}
          {error && <p role="alert" className="text-sm text-red-700 dark:text-red-300">{error}</p>}
        </form>
      </CardContent>
    </Card>
  )
}
