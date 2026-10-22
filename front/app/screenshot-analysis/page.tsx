'use client'

import { ChangeEvent, FormEvent, useEffect, useRef, useState } from 'react'
import { BiBrain, BiDownload, BiImage, BiSave } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import PageHeader from '@/app/components/PageHeader'
import { API_BASE } from '@/lib/api'
import {
  ANALYSIS_TIMEOUT_MS, MAX_SCREENSHOT_COUNT, OFFLINE_SCREENSHOT_HEADER, ScreenshotConfig, ScreenshotResult,
  apiErrorMessage, buildScreenshotConfigRequest, buildScreenshotRules,
  parseScreenshotConfig, parseScreenshotResult, screenshotDecisionLabels, validateScreenshotFiles,
} from '@/lib/screenshot-analysis'

const endpoint = `${API_BASE}/api/offline-screenshots`
const fieldClass = 'space-y-2 text-sm text-black dark:text-white'
type SelectedScreenshot = { id: number; file: File }

function ScreenshotPreview({ file, index }: { file: File; index: number }) {
  const imageRef = useRef<HTMLImageElement>(null)
  useEffect(() => {
    const image = imageRef.current
    if (!image) return
    const url = URL.createObjectURL(file)
    image.src = url
    return () => { image.removeAttribute('src'); URL.revokeObjectURL(url) }
  }, [file])
  return (
    <div className="max-h-[420px] overflow-auto rounded-lg border border-stroke bg-gray-50 p-2 dark:border-strokedark dark:bg-black">
      {/* Local object URL only: no remote image loader or upload occurs for preview. */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img ref={imageRef} alt={`第${index + 1}张待识别的本地岗位截图：${file.name}`} className="mx-auto h-auto max-w-full" />
    </div>
  )
}

export default function ScreenshotAnalysisPage() {
  const [config, setConfig] = useState<ScreenshotConfig | null>(null)
  const [baseUrl, setBaseUrl] = useState('')
  const [model, setModel] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [clearApiKey, setClearApiKey] = useState(false)
  const [loadingConfig, setLoadingConfig] = useState(true)
  const [saving, setSaving] = useState(false)
  const [analyzing, setAnalyzing] = useState(false)
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const [screenshots, setScreenshots] = useState<SelectedScreenshot[]>([])
  const [consent, setConsent] = useState(false)
  const [result, setResult] = useState<ScreenshotResult | null>(null)
  const [roleTerms, setRoleTerms] = useState('java,后端,服务端')
  const [requireInternship, setRequireInternship] = useState(true)
  const [daily, setDaily] = useState('125')
  const [monthly, setMonthly] = useState('5000')
  const [cities, setCities] = useState('')
  const [graduationYear, setGraduationYear] = useState('2028')
  const busy = useRef(false)
  const nextScreenshotId = useRef(0)
  const request = useRef<AbortController | null>(null)
  const configRequest = useRef<AbortController | null>(null)
  const saveRequest = useRef<AbortController | null>(null)
  const configDirty = !!config && (baseUrl.trim() !== config.baseUrl || model.trim() !== config.model || !!apiKey.trim() || clearApiKey)

  useEffect(() => {
    const controller = new AbortController()
    configRequest.current = controller
    const timer = setTimeout(() => controller.abort(), 15_000)
    void (async () => {
      try {
        const response = await fetch(`${endpoint}/config`, { signal: controller.signal, cache: 'no-store' })
        const payload: unknown = await response.json()
        if (!response.ok) throw new Error(apiErrorMessage(payload, '读取配置失败'))
        const next = parseScreenshotConfig(payload)
        if (!controller.signal.aborted) { setConfig(next); setBaseUrl(next.baseUrl); setModel(next.model) }
      } catch (caught) {
        if (!controller.signal.aborted) setError(caught instanceof Error ? caught.message : '读取配置失败')
        else if (configRequest.current === controller) setError('读取配置超时，请刷新页面重试')
      } finally {
        clearTimeout(timer)
        if (configRequest.current === controller) setLoadingConfig(false)
      }
    })()
    return () => { configRequest.current = null; controller.abort(); clearTimeout(timer) }
  }, [])

  useEffect(() => () => { request.current?.abort(); saveRequest.current?.abort() }, [])

  const saveConfig = async (event: FormEvent) => {
    event.preventDefault()
    if (busy.current) return
    setError(''); setNotice('')
    let body: ReturnType<typeof buildScreenshotConfigRequest>
    try { body = buildScreenshotConfigRequest(baseUrl, model, apiKey, clearApiKey) }
    catch (caught) { setError(caught instanceof Error ? caught.message : '配置格式错误'); return }
    busy.current = true
    setSaving(true)
    const controller = new AbortController()
    saveRequest.current = controller
    const timer = setTimeout(() => controller.abort(), 15_000)
    try {
      const response = await fetch(`${endpoint}/config`, {
        method: 'POST', headers: { 'Content-Type': 'application/json', ...OFFLINE_SCREENSHOT_HEADER },
        body: JSON.stringify(body), signal: controller.signal,
      })
      const payload: unknown = await response.json()
      if (!response.ok) throw new Error(apiErrorMessage(payload, '配置保存失败'))
      const next = parseScreenshotConfig(payload)
      setConfig(next); setBaseUrl(next.baseUrl); setModel(next.model)
      setApiKey(''); setClearApiKey(false)
      setNotice(next.ready ? '截图识别配置已保存。密钥不会回传到页面。' : '配置已保存；请填写本模块的API Key后再识别。')
    } catch (caught) {
      setError(controller.signal.aborted ? '保存请求已超时，不能确认是否保存成功；输入已保留，请刷新核对状态。'
        : caught instanceof Error ? caught.message : '配置保存失败，输入已保留')
    } finally { clearTimeout(timer); saveRequest.current = null; setSaving(false); busy.current = false }
  }

  const updateScreenshots = (next: SelectedScreenshot[]) => {
    setScreenshots(next)
    setError(''); setNotice(''); setResult(null); setConsent(false)
  }

  const chooseFiles = (event: ChangeEvent<HTMLInputElement>) => {
    if (busy.current) return
    const selected = Array.from(event.target.files ?? [])
    event.target.value = ''
    if (!selected.length) return
    const invalid = validateScreenshotFiles([...screenshots.map(item => item.file), ...selected])
    if (invalid) { setError(invalid); setNotice(''); return }
    updateScreenshots([...screenshots, ...selected.map(file => ({ id: nextScreenshotId.current++, file }))])
  }

  const moveScreenshot = (index: number, direction: -1 | 1) => {
    if (busy.current) return
    const target = index + direction
    if (target < 0 || target >= screenshots.length) return
    const next = [...screenshots]
    const [moved] = next.splice(index, 1)
    next.splice(target, 0, moved)
    updateScreenshots(next)
  }

  const removeScreenshot = (id: number) => {
    if (busy.current) return
    updateScreenshots(screenshots.filter(item => item.id !== id))
  }

  const analyze = async (event: FormEvent) => {
    event.preventDefault()
    if (busy.current) return
    setError(''); setNotice('')
    if (!config?.ready) { setError('请先保存可用的截图识别API配置和独立API Key'); return }
    if (configDirty) { setError('API配置尚未保存，请先保存再识别'); return }
    if (!screenshots.length || !consent) { setError('请选择同一岗位的截图，并确认将这组图片发送到所配置AI服务'); return }
    const invalid = validateScreenshotFiles(screenshots.map(item => item.file))
    if (invalid) { setError(invalid); return }
    let rules: ReturnType<typeof buildScreenshotRules>
    try { rules = buildScreenshotRules({ roleTerms, requireInternship, daily, monthly, cities, graduationYear }) }
    catch (caught) { setError(caught instanceof Error ? caught.message : '筛选条件格式错误'); return }
    const form = new FormData()
    screenshots.forEach(({ file }) => form.append('files', file))
    form.append('rules', JSON.stringify(rules))
    form.append('consent', 'true')
    busy.current = true
    setAnalyzing(true); setResult(null)
    const controller = new AbortController()
    request.current = controller
    const timer = setTimeout(() => controller.abort(), ANALYSIS_TIMEOUT_MS)
    try {
      const response = await fetch(`${endpoint}/analyze`, {
        method: 'POST', headers: OFFLINE_SCREENSHOT_HEADER, body: form, signal: controller.signal,
      })
      const payload: unknown = await response.json()
      if (!response.ok) throw new Error(apiErrorMessage(payload, '截图识别失败'))
      const next = parseScreenshotResult(payload)
      setResult(next)
      setNotice(next.status === 'ERROR' ? '本次识别失败，已按不符合处理并保存错误结果；没有执行投递。' : '离线识别与Java筛选完成，结果已保存；没有执行投递。')
    } catch (caught) {
      setError(controller.signal.aborted
        ? '等待已超时或取消。这里只停止浏览器等待，不能保证远端AI调用已取消；不要立即重复提交。'
        : caught instanceof Error ? caught.message : '截图识别失败')
    } finally { clearTimeout(timer); request.current = null; setAnalyzing(false); busy.current = false }
  }

  const downloadResult = () => {
    if (!result) return
    const blob = new Blob([JSON.stringify(result, null, 2)], { type: 'application/json;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url; link.download = `screenshot-analysis-${result.id.replace(/[^a-zA-Z0-9_-]/g, '')}.json`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  return (
    <div className="space-y-6">
      <PageHeader icon={<BiImage />} title="截图识别" subtitle="上传已保存的岗位截图，自动提取、校验和筛选；不连接招聘页面，不发送消息。" iconClass="text-white" accentBgClass="bg-cyan-600" />
      <div className="rounded-xl border border-cyan-300 bg-cyan-50 p-4 text-sm text-cyan-950 dark:border-cyan-700 dark:bg-cyan-950/30 dark:text-cyan-100">
        独立离线分析入口：只处理你主动上传的PNG/JPEG截图，不截图、不控制浏览器、不启动任何平台投递任务。原图与判断结果保留在本机，便于核对。
      </div>
      {error && <div role="alert" className="rounded-xl border border-red-300 bg-red-50 p-4 text-sm text-red-900 dark:border-red-800 dark:bg-red-950/30 dark:text-red-200">{error}</div>}
      {notice && <div role="status" className="rounded-xl border border-cyan-300 bg-cyan-50 p-4 text-sm text-cyan-950 dark:border-cyan-800 dark:bg-cyan-950/30 dark:text-cyan-100">{notice}</div>}

      <Card>
        <CardHeader><CardTitle className="flex items-center gap-2"><BiBrain /> 识别API配置</CardTitle><CardDescription>API地址和模型可沿用原配置；API Key须在本页单独填写一次。保存不会修改打招呼配置。</CardDescription></CardHeader>
        <CardContent>
          <form onSubmit={saveConfig} className="space-y-4">
            <div className="grid gap-4 md:grid-cols-2">
              <label className={fieldClass}>API Base URL<Input value={baseUrl} onChange={event => setBaseUrl(event.target.value)} placeholder="https://api.deepseek.com" disabled={loadingConfig || saving || analyzing} maxLength={2048} required /></label>
              <label className={fieldClass}>模型名称<Input value={model} onChange={event => setModel(event.target.value)} placeholder="deepseek-flash" disabled={loadingConfig || saving || analyzing} maxLength={160} required /></label>
            </div>
            <label className={`block ${fieldClass}`}>本模块API Key<Input type="password" value={apiKey} onChange={event => setApiKey(event.target.value)} placeholder={config?.apiKeyConfigured ? '已配置；留空保留原密钥' : '请在本机填写API Key'} autoComplete="off" spellCheck={false} disabled={loadingConfig || saving || analyzing || clearApiKey} maxLength={4096} /></label>
            <div className="flex flex-wrap items-center gap-4 text-sm text-waterloo dark:text-manatee">
              <span aria-live="polite">密钥状态：{loadingConfig ? '正在读取…' : config?.apiKeyConfigured ? '已配置（不会回传密钥）' : '未配置'}</span>
              {config && <span>地址/模型来源：{config.configSource === 'EXISTING' ? '原AI配置' : '截图识别专用配置'}</span>}
            </div>
            <label className="flex items-center gap-2 text-sm text-black dark:text-white"><input type="checkbox" checked={clearApiKey} onChange={event => setClearApiKey(event.target.checked)} disabled={loadingConfig || saving || analyzing || !!apiKey.trim()} /> 清除本模块已保存的密钥（保存后生效）</label>
            <Button type="submit" disabled={loadingConfig || saving || analyzing}><BiSave /> {saving ? '正在保存…' : '保存识别配置'}</Button>
          </form>
        </CardContent>
      </Card>

      <form onSubmit={analyze} className="space-y-6">
        <Card>
          <CardHeader><CardTitle>Java筛选条件</CardTitle><CardDescription>模型只提取可见事实，是否符合由Java规则判断。截图未提供的字段（含毕业届次）保留为空，并按符合处理；无法识别、信息矛盾或明确不符合时按不符合处理，不会猜测补全。</CardDescription></CardHeader>
          <CardContent className="grid gap-4 md:grid-cols-2">
            <label className={fieldClass}>岗位方向（逗号分隔，最多20项）<Input value={roleTerms} onChange={event => setRoleTerms(event.target.value)} disabled={analyzing || saving} maxLength={1619} required /></label>
            <label className={fieldClass}>城市（留空不限，最多20项）<Input value={cities} onChange={event => setCities(event.target.value)} disabled={analyzing || saving} maxLength={1619} /></label>
            <label className={fieldClass}>最低日薪（元/天）<Input type="number" min="0" max="1000000" step="0.01" value={daily} onChange={event => setDaily(event.target.value)} disabled={analyzing || saving} required /></label>
            <label className={fieldClass}>最低月薪（元/月）<Input type="number" min="0" max="1000000" step="0.01" value={monthly} onChange={event => setMonthly(event.target.value)} disabled={analyzing || saving} required /></label>
            <label className={fieldClass}>毕业年份（留空不筛届次）<Input type="number" min="2000" max="2100" step="1" value={graduationYear} onChange={event => setGraduationYear(event.target.value)} disabled={analyzing || saving} /></label>
            <label className="flex items-center gap-2 self-center text-sm text-black dark:text-white"><input type="checkbox" checked={requireInternship} onChange={event => setRequireInternship(event.target.checked)} disabled={analyzing || saving} /> 需要实习岗位信息</label>
          </CardContent>
        </Card>

        <Card>
          <CardHeader><CardTitle>上传同一岗位的截图</CardTitle><CardDescription>可添加1—6张已保存的PNG或JPEG，每张不超过8 MiB，总计不超过24 MiB。请按同一岗位从上到下的阅读顺序排列，上下屏可以重叠，模型会联合提取为一份岗位信息。请避免上传密码、验证码、API Key、联系方式或私人聊天内容。</CardDescription></CardHeader>
          <CardContent className="space-y-4">
            <Input type="file" multiple accept="image/png,image/jpeg,.png,.jpg,.jpeg" onChange={chooseFiles} disabled={analyzing || saving || screenshots.length >= MAX_SCREENSHOT_COUNT} aria-label="添加同一岗位的截图（可多选）" aria-describedby="screenshot-selection-summary" />
            <p id="screenshot-selection-summary" aria-live="polite" className="text-sm text-waterloo dark:text-manatee">已选择{screenshots.length} / {MAX_SCREENSHOT_COUNT}张 · 总计{(screenshots.reduce((sum, item) => sum + item.file.size, 0) / (1024 * 1024)).toFixed(2)} MiB。可继续添加，调整顺序或移除后需重新确认发送。</p>
            {screenshots.length > 0 && <ol className="space-y-4" aria-label="截图识别顺序">
              {screenshots.map(({ id, file }, index) => <li key={id} className="space-y-3 rounded-xl border border-stroke p-3 dark:border-strokedark">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <p className="break-all text-sm text-black dark:text-white">第{index + 1}张 · {file.name} · {(file.size / 1024).toFixed(1)} KiB</p>
                  <div className="flex flex-wrap gap-2">
                    <Button type="button" size="sm" variant="outline" onClick={() => moveScreenshot(index, -1)} disabled={analyzing || saving || index === 0} aria-label={`上移第${index + 1}张截图：${file.name}`}>上移</Button>
                    <Button type="button" size="sm" variant="outline" onClick={() => moveScreenshot(index, 1)} disabled={analyzing || saving || index === screenshots.length - 1} aria-label={`下移第${index + 1}张截图：${file.name}`}>下移</Button>
                    <Button type="button" size="sm" variant="outline" onClick={() => removeScreenshot(id)} disabled={analyzing || saving} aria-label={`移除第${index + 1}张截图：${file.name}`}>移除</Button>
                  </div>
                </div>
                <ScreenshotPreview file={file} index={index} />
              </li>)}
            </ol>}
            <label className="flex items-start gap-2 text-sm text-black dark:text-white"><input type="checkbox" className="mt-1" checked={consent} onChange={event => setConsent(event.target.checked)} disabled={analyzing || saving || !screenshots.length} /><span>我确认有权处理这组图片，且同意按上述顺序将全部图片发送到上面配置的AI服务进行联合识别，可能产生API费用。此操作不会投递或发送招呼。</span></label>
            {configDirty && <p className="text-sm text-amber-700 dark:text-amber-300">识别API配置有未保存修改，请先保存。</p>}
            <div className="flex flex-wrap items-center gap-3">
              <Button type="submit" disabled={loadingConfig || saving || analyzing || !config?.ready || configDirty || !screenshots.length || !consent}><BiImage /> {analyzing ? '正在联合识别和筛选…' : '识别并保存判断结果'}</Button>
              {analyzing && <Button type="button" variant="outline" onClick={() => request.current?.abort()}>停止等待</Button>}
              <span className="text-xs text-waterloo dark:text-manatee">不自动重试；超时或停止等待不代表远端AI调用一定取消。</span>
            </div>
          </CardContent>
        </Card>
      </form>

      {result && (
        <Card>
          <CardHeader><div className="flex flex-wrap items-center justify-between gap-3"><CardTitle>{screenshotDecisionLabels[result.evaluation.decision]}</CardTitle><Button type="button" variant="outline" onClick={downloadResult}><BiDownload /> 下载结果JSON</Button></div><CardDescription>仅为截图识别与规则判断，不代表已投递、已联系或岗位资格已获招聘方确认。字段缺失按符合处理，不代表缺失内容已经得到事实确认。</CardDescription></CardHeader>
          <CardContent className="space-y-5 text-sm">
            <dl className="grid gap-3 text-waterloo dark:text-manatee md:grid-cols-2"><div><dt>记录ID</dt><dd className="break-all text-black dark:text-white">{result.id}</dd></div><div><dt>保存时间</dt><dd className="text-black dark:text-white">{result.createdAt}</dd></div><div><dt>截图（共{result.imageCount ?? 1}张，按识别顺序）</dt><dd className="break-all text-black dark:text-white"><ol className="list-inside list-decimal space-y-1">{(result.sources ?? [{ index: 1, sourceName: result.sourceName, imageSha256: result.imageSha256 }]).map(source => <li key={source.index}>{source.sourceName}</li>)}</ol></dd></div><div><dt>识别模型</dt><dd className="break-all text-black dark:text-white">{result.model || '未调用或未确认'}</dd></div></dl>
            {result.errorMessage && <p role="alert" className="rounded-lg bg-red-50 p-3 text-red-900 dark:bg-red-950/30 dark:text-red-200">{result.errorMessage}</p>}
            {result.status === 'ERROR' && <p className="rounded-lg bg-red-50 p-3 text-red-900 dark:bg-red-950/30 dark:text-red-200">识别或解析未成功，本次判断为不符合；没有执行投递。</p>}
            {result.evaluation.decision === 'MATCH' && <p className="rounded-lg border border-cyan-300 bg-cyan-50 p-3 text-cyan-950 dark:border-cyan-800 dark:bg-cyan-950/30 dark:text-cyan-100">按当前规则判为符合。缺失字段仍保留为空并按符合处理，这不代表截图中未提供的信息已经得到确认。</p>}
            <div><h3 className="mb-2 font-semibold text-black dark:text-white">判断依据</h3><ul className="list-inside list-disc space-y-1 text-waterloo dark:text-manatee">{result.evaluation.reasons.map((reason, index) => <li key={index}>{reason}</li>)}</ul></div>
            {result.evaluation.warnings.length > 0 && <div className="rounded-lg border border-amber-300 bg-amber-50 p-3 text-amber-950 dark:border-amber-700 dark:bg-amber-950/20 dark:text-amber-200"><h3 className="mb-2 font-semibold">校验提醒</h3><ul className="list-inside list-disc space-y-1">{result.evaluation.warnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul></div>}
            {result.fields && <><h3 className="font-semibold text-black dark:text-white">合并识别的岗位信息</h3><dl className="grid gap-4 rounded-xl border border-stroke p-4 dark:border-strokedark md:grid-cols-2">{[
              ['岗位', result.fields.jobTitle], ['公司', result.fields.company], ['城市', result.fields.city], ['薪资原文', result.fields.salaryText],
              ['薪资单位', result.fields.salaryUnit === 'DAY' ? '元/天' : result.fields.salaryUnit === 'MONTH' ? '元/月' : null],
              ['薪资下限／上限', `${result.fields.salaryMin ?? '未知'} ／ ${result.fields.salaryMax ?? '未知'}`],
              ['每周天数', result.fields.daysPerWeek == null ? null : `${result.fields.daysPerWeek}天`],
              ['实习时长', result.fields.durationMonths == null ? null : `${result.fields.durationMonths}个月`],
              ['学历', result.fields.education], ['毕业届次', result.fields.graduationYears.length ? result.fields.graduationYears.join('、') : null],
              ['技术关键词', result.fields.skills.length ? result.fields.skills.join('、') : null],
              ['不确定字段', result.fields.uncertainFields.length ? result.fields.uncertainFields.join('、') : '模型未标记；不代表识别完全正确'],
            ].map(([label, value]) => <div key={label}><dt className="text-waterloo dark:text-manatee">{label}</dt><dd className="mt-1 break-words text-black dark:text-white">{value || '未识别／截图未提供'}</dd></div>)}</dl><div><h3 className="mb-2 font-semibold text-black dark:text-white">合并识别的岗位描述</h3><p className="whitespace-pre-wrap break-words text-waterloo dark:text-manatee">{result.fields.description || '截图未提供或无法识别'}</p></div></>}
          </CardContent>
        </Card>
      )}
    </div>
  )
}
