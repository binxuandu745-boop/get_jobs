/** Offline screenshot analysis only. No browser automation or delivery API is used here. */
export const MAX_SCREENSHOT_BYTES = 8 * 1024 * 1024
export const MAX_SCREENSHOT_COUNT = 6
export const MAX_SCREENSHOTS_TOTAL_BYTES = 24 * 1024 * 1024
export const ANALYSIS_TIMEOUT_MS = 75_000
export const OFFLINE_SCREENSHOT_HEADER = { 'X-Offline-Screenshot': '1' } as const

export type ScreenshotDecision = 'MATCH' | 'MISMATCH' | 'REVIEW' | 'ERROR'
export type ScreenshotConfig = {
  baseUrl: string
  model: string
  apiKeyConfigured: boolean
  keySource: 'SCREENSHOT' | 'MISSING'
  configSource: 'SCREENSHOT' | 'EXISTING'
  ready: boolean
}
export type ScreenshotRules = {
  roleTerms: string[]
  requireInternship: boolean
  minimumDailySalary: number
  minimumMonthlySalary: number
  cities: string[]
  graduationYear: number | null
}
export type ScreenshotFields = {
  jobTitle: string | null
  company: string | null
  city: string | null
  salaryText: string | null
  salaryMin: number | null
  salaryMax: number | null
  salaryUnit: 'DAY' | 'MONTH' | 'UNKNOWN'
  daysPerWeek: number | null
  durationMonths: number | null
  education: string | null
  skills: string[]
  graduationYears: number[]
  description: string | null
  uncertainFields: string[]
}
export type ScreenshotSource = { index: number; sourceName: string; imageSha256: string }
export type ScreenshotResult = {
  id: string
  status: 'COMPLETE' | 'ERROR'
  createdAt: string
  sourceName: string
  imageSha256: string
  sources?: ScreenshotSource[]
  imageCount?: number
  fields: ScreenshotFields | null
  evaluation: { decision: ScreenshotDecision; reasons: string[]; warnings: string[] }
  errorCode?: string | null
  errorMessage?: string | null
  model: string
}

export function defaultScreenshotRules(): ScreenshotRules {
  return {
    roleTerms: ['java', '后端', '服务端'], requireInternship: true,
    minimumDailySalary: 125, minimumMonthlySalary: 5000, cities: [], graduationYear: 2028,
  }
}

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('服务器返回了无法识别的数据格式')
  return value as Record<string, unknown>
}
function text(value: unknown, maximum = 20_000): string {
  if (typeof value !== 'string' || value.length > maximum) throw new Error('服务器返回的文字字段格式不正确')
  return value
}
function nullableText(value: unknown): string | null {
  return value == null ? null : text(value)
}
function strings(value: unknown): string[] {
  if (!Array.isArray(value) || value.length > 200) throw new Error('服务器返回的列表格式不正确')
  return value.map(item => text(item, 2000))
}
function nullableNumber(value: unknown): number | null {
  if (value == null) return null
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > 1_000_000_000) {
    throw new Error('服务器返回的数值字段格式不正确')
  }
  return value
}

export function parseScreenshotConfig(payload: unknown): ScreenshotConfig {
  const envelope = object(payload)
  if (envelope.success !== true) throw new Error(apiErrorMessage(payload, '无法读取截图识别配置'))
  const data = object(envelope.data)
  if (typeof data.apiKeyConfigured !== 'boolean' || typeof data.ready !== 'boolean'
    || !['SCREENSHOT', 'MISSING'].includes(String(data.keySource))
    || !['SCREENSHOT', 'EXISTING'].includes(String(data.configSource))) {
    throw new Error('截图识别配置状态不完整')
  }
  return {
    baseUrl: text(data.baseUrl, 2048), model: text(data.model, 160),
    apiKeyConfigured: data.apiKeyConfigured, ready: data.ready,
    keySource: data.keySource as ScreenshotConfig['keySource'],
    configSource: data.configSource as ScreenshotConfig['configSource'],
  }
}

export function validateScreenshotFile(file: { name: string; size: number; type: string }): string | null {
  if (!Number.isFinite(file.size) || file.size <= 0) return '请选择非空截图文件'
  if (file.size > MAX_SCREENSHOT_BYTES) return '截图不能超过8 MiB'
  const allowedType = ['image/png', 'image/jpeg'].includes(file.type.toLowerCase())
  if (!allowedType && !(file.type === '' && /\.(png|jpe?g)$/i.test(file.name))) return '仅支持PNG或JPEG截图'
  return null
}

export function validateScreenshotFiles(files: readonly { name: string; size: number; type: string }[]): string | null {
  if (!files.length) return '请至少选择1张同一岗位的截图'
  if (files.length > MAX_SCREENSHOT_COUNT) return '同一岗位最多选择6张截图'
  let totalBytes = 0
  for (let index = 0; index < files.length; index++) {
    const invalid = validateScreenshotFile(files[index])
    if (invalid) return `第${index + 1}张：${invalid}`
    totalBytes += files[index].size
  }
  if (totalBytes > MAX_SCREENSHOTS_TOTAL_BYTES) return '截图总大小不能超过24 MiB'
  return null
}

export function buildScreenshotConfigRequest(baseUrl: string, model: string, apiKey: string, clearApiKey: boolean) {
  const cleanBase = baseUrl.trim()
  const cleanModel = model.trim()
  let parsed: URL
  try { parsed = new URL(cleanBase) } catch { throw new Error('请填写完整的API Base URL') }
  const local = ['localhost', '127.0.0.1', '[::1]'].includes(parsed.hostname)
  if ((parsed.protocol !== 'https:' && !(parsed.protocol === 'http:' && local))
    || parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error('API地址必须使用HTTPS（本机测试可使用HTTP），且不能包含用户名、密钥或查询参数')
  }
  if (cleanBase.length > 2048 || !cleanModel || cleanModel.length > 160) throw new Error('请填写有效的API地址和模型名称（模型名称不超过160字）')
  const cleanKey = apiKey.trim()
  if (cleanKey.length > 4096 || /[\s\x00-\x1f\x7f]/.test(cleanKey)) throw new Error('API Key不能超过4096字符，且不能包含空白或控制字符')
  if (clearApiKey && cleanKey) throw new Error('清除密钥与填写新密钥不能同时选择')
  return {
    baseUrl: cleanBase, model: cleanModel,
    ...(cleanKey ? { apiKey: cleanKey } : {}),
    ...(clearApiKey ? { clearApiKey: true } : {}),
  }
}

export function buildScreenshotRules(input: {
  roleTerms: string; requireInternship: boolean; daily: string; monthly: string; cities: string; graduationYear: string
}): ScreenshotRules {
  const split = (value: string) => value.split(/[,，\n]/).map(item => item.trim()).filter(Boolean)
  const roleTerms = split(input.roleTerms)
  const cities = split(input.cities)
  if (!roleTerms.length || roleTerms.length > 20 || roleTerms.some(term => term.length > 80)) throw new Error('请填写1—20个岗位方向关键词，每个不超过80字')
  if (cities.length > 20 || cities.some(city => city.length > 80)) throw new Error('城市最多20项，每项不超过80字')
  const salary = (raw: string) => {
    if (!/^\d+(?:\.\d{1,2})?$/.test(raw.trim())) throw new Error('最低薪资须为非负数字，最多两位小数')
    const value = Number(raw)
    if (!Number.isFinite(value) || value > 1_000_000) throw new Error('最低薪资超出允许范围')
    return value
  }
  const rawYear = input.graduationYear.trim()
  const graduationYear = rawYear ? Number(rawYear) : null
  if (graduationYear !== null && (!/^\d{4}$/.test(rawYear) || graduationYear < 2000 || graduationYear > 2100)) {
    throw new Error('毕业年份须为2000—2100之间的四位年份，也可以留空')
  }
  return { roleTerms, requireInternship: input.requireInternship, minimumDailySalary: salary(input.daily), minimumMonthlySalary: salary(input.monthly), cities, graduationYear }
}

export function parseScreenshotResult(payload: unknown): ScreenshotResult {
  const envelope = object(payload)
  if (envelope.success !== true) throw new Error(apiErrorMessage(payload, '截图识别请求失败'))
  const data = object(envelope.data)
  if (!['COMPLETE', 'ERROR'].includes(String(data.status))) throw new Error('无法确认识别任务状态')
  const evaluation = object(data.evaluation)
  if (!['MATCH', 'MISMATCH', 'REVIEW', 'ERROR'].includes(String(evaluation.decision))) throw new Error('无法确认Java筛选结果')
  let fields: ScreenshotFields | null = null
  if (data.fields != null) {
    const raw = object(data.fields)
    if (!['DAY', 'MONTH', 'UNKNOWN'].includes(String(raw.salaryUnit))) throw new Error('薪资单位格式不正确')
    if (!Array.isArray(raw.graduationYears) || raw.graduationYears.length > 100
      || raw.graduationYears.some(year => typeof year !== 'number' || !Number.isInteger(year) || year < 2000 || year > 2100)) {
      throw new Error('毕业届次格式不正确')
    }
    fields = {
      jobTitle: nullableText(raw.jobTitle), company: nullableText(raw.company), city: nullableText(raw.city),
      salaryText: nullableText(raw.salaryText), salaryMin: nullableNumber(raw.salaryMin), salaryMax: nullableNumber(raw.salaryMax),
      salaryUnit: raw.salaryUnit as ScreenshotFields['salaryUnit'], daysPerWeek: nullableNumber(raw.daysPerWeek), durationMonths: nullableNumber(raw.durationMonths),
      education: nullableText(raw.education), skills: strings(raw.skills), graduationYears: raw.graduationYears as number[],
      description: nullableText(raw.description), uncertainFields: strings(raw.uncertainFields),
    }
  }
  if (data.status === 'COMPLETE' && fields === null) throw new Error('识别结果缺少字段数据')
  if (data.status === 'COMPLETE' && evaluation.decision === 'ERROR') throw new Error('识别状态与筛选结果不一致')
  // New failure records keep the technical ERROR status but evaluate as MISMATCH.
  // ERROR + ERROR remains readable for records created before this rule changed.
  if (data.status === 'ERROR' && !['MISMATCH', 'ERROR'].includes(String(evaluation.decision))) throw new Error('识别状态与筛选结果不一致')
  if (data.status === 'ERROR' && fields !== null) throw new Error('失败识别结果不应包含有效字段')
  const sourceName = text(data.sourceName, 500)
  const imageSha256 = text(data.imageSha256, 128)
  let sourceMetadata: Pick<ScreenshotResult, 'sources' | 'imageCount'> = {}
  // Old saved records contain only the first image's metadata. New records must
  // describe the complete ordered image set consistently, including failures.
  if (data.sources !== undefined || data.imageCount !== undefined) {
    if (typeof data.imageCount !== 'number' || !Number.isInteger(data.imageCount)
      || data.imageCount < 1 || data.imageCount > MAX_SCREENSHOT_COUNT
      || !Array.isArray(data.sources) || data.sources.length !== data.imageCount) {
      throw new Error('截图来源数量不一致')
    }
    const sources = data.sources.map((value, index): ScreenshotSource => {
      const source = object(value)
      const name = text(source.sourceName, 500)
      const hash = text(source.imageSha256, 64)
      if (source.index !== index + 1 || !name.trim() || /[\x00-\x1f\x7f]/.test(name) || !/^[a-fA-F0-9]{64}$/.test(hash)) {
        throw new Error('截图来源顺序或文件信息不正确')
      }
      return { index: index + 1, sourceName: name, imageSha256: hash }
    })
    if (sources[0].sourceName !== sourceName || sources[0].imageSha256 !== imageSha256) throw new Error('首张截图来源信息不一致')
    sourceMetadata = { sources, imageCount: data.imageCount }
  }
  return {
    id: text(data.id, 100), status: data.status as ScreenshotResult['status'], createdAt: text(data.createdAt, 100),
    sourceName, imageSha256, ...sourceMetadata, fields,
    evaluation: { decision: evaluation.decision as ScreenshotDecision, reasons: strings(evaluation.reasons), warnings: strings(evaluation.warnings) },
    errorCode: nullableText(data.errorCode), errorMessage: nullableText(data.errorMessage), model: text(data.model, 160),
  }
}

export function apiErrorMessage(payload: unknown, fallback: string): string {
  if (payload && typeof payload === 'object' && !Array.isArray(payload)) {
    const message = (payload as Record<string, unknown>).message
    if (typeof message === 'string' && message.trim() && message.length <= 500) return message
  }
  return fallback
}

export const screenshotDecisionLabels: Record<ScreenshotDecision, string> = {
  MATCH: '符合条件', MISMATCH: '不符合', REVIEW: '旧版待复核结果', ERROR: '识别失败，按不符合处理',
}
