const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')

const source = fs.readFileSync(path.join(__dirname, '..', 'lib', 'screenshot-analysis.ts'), 'utf8')
const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS } }).outputText
const exportsValue = {}
vm.runInNewContext(code, { exports: exportsValue, URL }, { filename: 'screenshot-analysis.ts' })
const helpers = exportsValue
const plain = value => JSON.parse(JSON.stringify(value))
const config = overrides => ({ success: true, data: {
  baseUrl: 'https://api.deepseek.com', model: 'deepseek-flash', apiKeyConfigured: false,
  keySource: 'MISSING', configSource: 'EXISTING', ready: false, ...overrides,
} })
const fields = overrides => ({
  jobTitle: 'Java开发实习生', company: null, city: '天津', salaryText: '150-200元/天', salaryMin: 150, salaryMax: 200,
  salaryUnit: 'DAY', daysPerWeek: 4, durationMonths: 3, education: '本科', skills: ['Java'], graduationYears: [],
  description: null, uncertainFields: [], ...overrides,
})
const result = overrides => ({ success: true, data: {
  id: 'result-id', status: 'COMPLETE', createdAt: '2026-10-21T12:00:00', sourceName: 'job.png', imageSha256: 'abc',
  fields: fields(), evaluation: { decision: 'MATCH', reasons: ['已识别条件符合'], warnings: ['截图未提供毕业届次，缺失字段按符合处理'] },
  model: 'deepseek-flash', ...overrides,
} })
const multiSources = count => Array.from({ length: count }, (_, index) => ({
  index: index + 1, sourceName: `job-${index + 1}.png`, imageSha256: String(index + 1).repeat(64),
}))
const multiResult = (sources, overrides = {}) => result({
  sourceName: sources[0].sourceName, imageSha256: sources[0].imageSha256,
  sources, imageCount: sources.length, ...overrides,
})

test('config state contains only metadata and never propagates a server key', () => {
  const parsed = helpers.parseScreenshotConfig(config({ apiKey: 'must-not-return', apiKeyConfigured: true, keySource: 'SCREENSHOT', ready: true }))
  assert.equal(parsed.ready, true)
  assert.equal('apiKey' in parsed, false)
  assert.throws(() => helpers.parseScreenshotConfig(config({ keySource: 'EXISTING' })))
  assert.throws(() => helpers.parseScreenshotConfig(config({ ready: 'true' })))
})

test('blank password retains the saved key; explicit clear cannot send a replacement simultaneously', () => {
  assert.deepEqual(plain(helpers.buildScreenshotConfigRequest(' https://api.deepseek.com ', ' deepseek-flash ', ' ', false)), { baseUrl: 'https://api.deepseek.com', model: 'deepseek-flash' })
  assert.equal(helpers.buildScreenshotConfigRequest('https://api.deepseek.com', 'deepseek-flash', '', true).clearApiKey, true)
  assert.throws(() => helpers.buildScreenshotConfigRequest('https://api.deepseek.com', 'deepseek-flash', 'new-key', true))
  assert.equal(helpers.buildScreenshotConfigRequest('https://api.deepseek.com', 'deepseek-flash', ' new-key ', false).apiKey, 'new-key')
})

test('configuration rejects insecure remote URLs and embedded secrets', () => {
  for (const url of ['http://example.com', 'https://user:pass@example.com', 'https://example.com?key=secret', 'https://example.com#secret', 'file:///tmp/api']) {
    assert.throws(() => helpers.buildScreenshotConfigRequest(url, 'model', '', false))
  }
  assert.doesNotThrow(() => helpers.buildScreenshotConfigRequest('http://127.0.0.1:8000/v1', 'model', '', false))
})

test('configuration matches server model and key length limits and forbids key whitespace', () => {
  assert.doesNotThrow(() => helpers.buildScreenshotConfigRequest('https://api.deepseek.com', 'm'.repeat(160), 'k'.repeat(4096), false))
  assert.throws(() => helpers.buildScreenshotConfigRequest('https://api.deepseek.com', 'm'.repeat(161), '', false))
  assert.throws(() => helpers.buildScreenshotConfigRequest('https://api.deepseek.com', 'model', 'k'.repeat(4097), false))
  for (const key of ['one two', 'one\ttwo', 'one\ntwo']) {
    assert.throws(() => helpers.buildScreenshotConfigRequest('https://api.deepseek.com', 'model', key, false))
  }
})

test('PNG and JPEG file validation enforces the exact 8 MiB boundary', () => {
  assert.equal(helpers.validateScreenshotFile({ name: 'job.png', size: 8 * 1024 * 1024, type: 'image/png' }), null)
  assert.equal(helpers.validateScreenshotFile({ name: 'job.jpg', size: 123, type: '' }), null)
  for (const file of [{ name: 'job.png', size: 0, type: 'image/png' }, { name: 'job.png', size: 8 * 1024 * 1024 + 1, type: 'image/png' }, { name: 'job.gif', size: 100, type: 'image/gif' }, { name: 'job.png', size: 100, type: 'text/plain' }]) {
    assert.equal(typeof helpers.validateScreenshotFile(file), 'string')
  }
})

test('one job allows one to six ordered screenshots within a combined 24 MiB', () => {
  const image = (size = 4 * 1024 * 1024, name = 'job.png') => ({ name, size, type: 'image/png' })
  assert.equal(helpers.MAX_SCREENSHOT_COUNT, 6)
  assert.equal(helpers.MAX_SCREENSHOTS_TOTAL_BYTES, 24 * 1024 * 1024)
  assert.equal(helpers.validateScreenshotFiles([image()]), null)
  assert.equal(helpers.validateScreenshotFiles(Array.from({ length: 6 }, () => image())), null)
  assert.equal(helpers.validateScreenshotFiles(Array.from({ length: 3 }, () => image(8 * 1024 * 1024))), null)
  assert.match(helpers.validateScreenshotFiles([]), /至少选择1张/)
  assert.match(helpers.validateScreenshotFiles(Array.from({ length: 7 }, () => image(1))), /最多选择6张/)
  assert.match(helpers.validateScreenshotFiles([image(8 * 1024 * 1024), image(8 * 1024 * 1024), image(8 * 1024 * 1024), image(1)]), /总大小不能超过24 MiB/)
  assert.match(helpers.validateScreenshotFiles([image(), image(8 * 1024 * 1024 + 1)]), /第2张：截图不能超过8 MiB/)
  assert.match(helpers.validateScreenshotFiles([image(), { name: 'lower.gif', size: 1, type: 'image/gif' }]), /第2张：仅支持PNG或JPEG/)
})

test('rule builder preserves independent daily and monthly amounts and explicit unknown graduation year', () => {
  const parsed = helpers.buildScreenshotRules({ roleTerms: 'java，后端, 服务端', requireInternship: true, daily: '125', monthly: '5000.50', cities: '天津,上海', graduationYear: '' })
  assert.deepEqual(plain(parsed.roleTerms), ['java', '后端', '服务端'])
  assert.equal(parsed.minimumDailySalary, 125)
  assert.equal(parsed.minimumMonthlySalary, 5000.5)
  assert.equal(parsed.graduationYear, null)
  for (const patch of [{ daily: '-1' }, { monthly: 'NaN' }, { graduationYear: '20.28' }, { roleTerms: '' }]) {
    assert.throws(() => helpers.buildScreenshotRules({ roleTerms: 'Java', requireInternship: true, daily: '125', monthly: '5000', cities: '', graduationYear: '2028', ...patch }))
  }
})

test('rule keywords and cities share the backend twenty-item and eighty-character bounds', () => {
  const baseline = { roleTerms: 'java', requireInternship: true, daily: '125', monthly: '5000', cities: '', graduationYear: '2028' }
  const twenty = Array.from({ length: 20 }, (_, index) => `term-${index}`).join(',')
  const twentyOne = `${twenty},extra`
  assert.doesNotThrow(() => helpers.buildScreenshotRules({ ...baseline, roleTerms: twenty, cities: twenty }))
  assert.doesNotThrow(() => helpers.buildScreenshotRules({ ...baseline, roleTerms: 'x'.repeat(80), cities: 'x'.repeat(80) }))
  for (const patch of [{ roleTerms: twentyOne }, { cities: twentyOne }, { roleTerms: 'x'.repeat(81) }, { cities: 'x'.repeat(81) }]) {
    assert.throws(() => helpers.buildScreenshotRules({ ...baseline, ...patch }))
  }
})

test('partial extraction can match while preserving null fields and missing-field warnings', () => {
  const parsed = helpers.parseScreenshotResult(result())
  assert.equal(parsed.evaluation.decision, 'MATCH')
  assert.equal(parsed.fields.company, null)
  assert.deepEqual(plain(parsed.fields.graduationYears), [])
  assert.equal(parsed.evaluation.warnings.some(warning => warning.includes('缺失字段按符合处理')), true)
  assert.equal(helpers.screenshotDecisionLabels.MATCH, '符合条件')
  assert.equal(JSON.stringify(helpers.screenshotDecisionLabels).includes('已投递'), false)
  assert.equal('sources' in parsed, false)
  assert.equal('imageCount' in parsed, false)
})

test('new result metadata preserves every screenshot in its submitted order, including single images', () => {
  for (const count of [1, 2, 6]) {
    const sources = multiSources(count)
    const parsed = helpers.parseScreenshotResult(multiResult(sources))
    assert.equal(parsed.imageCount, count)
    assert.deepEqual(plain(parsed.sources), sources)
    assert.equal(parsed.sourceName, sources[0].sourceName)
    assert.equal(parsed.imageSha256, sources[0].imageSha256)
    assert.equal(parsed.fields.jobTitle, 'Java开发实习生')
  }
  const failed = helpers.parseScreenshotResult(multiResult(multiSources(2), {
    status: 'ERROR', fields: null, evaluation: { decision: 'MISMATCH', reasons: ['联合识别失败'], warnings: [] },
  }))
  assert.equal(failed.sources.length, 2)
  assert.equal(failed.evaluation.decision, 'MISMATCH')
})

test('new screenshot metadata rejects missing, excessive, inconsistent, or out-of-order sources', () => {
  const sources = multiSources(2)
  for (const patch of [
    { sources: undefined }, { imageCount: undefined }, { sources: null }, { imageCount: null },
    { sources: [] }, { imageCount: 0 }, { imageCount: 7 }, { imageCount: 1.5 }, { imageCount: '2' },
    { sources: sources.slice(0, 1) }, { sources: multiSources(7), imageCount: 7 },
    { sources: [{ ...sources[0], index: 0 }, sources[1]] },
    { sources: [sources[0], { ...sources[1], index: 1 }] },
    { sources: [sources[0], { ...sources[1], index: 3 }] },
    { sources: [sources[0], { ...sources[1], index: '2' }] },
    { sources: [sources[1], sources[0]] },
    { sourceName: 'another.png' }, { imageSha256: 'a'.repeat(64) },
  ]) {
    assert.throws(() => helpers.parseScreenshotResult(multiResult(sources, patch)))
  }
})

test('each new screenshot source requires a bounded nonempty name and a SHA-256 hash', () => {
  const sources = multiSources(2)
  for (const patch of [
    { sourceName: '' }, { sourceName: '  ' }, { sourceName: 'a'.repeat(501) }, { sourceName: 'lower\n.png' },
    { sourceName: 1 }, { imageSha256: 'abc' }, { imageSha256: 'z'.repeat(64) },
    { imageSha256: '1'.repeat(65) }, { imageSha256: null },
  ]) {
    assert.throws(() => helpers.parseScreenshotResult(multiResult([sources[0], { ...sources[1], ...patch }])))
  }
})

test('recognition or parsing errors keep ERROR status and are evaluated as mismatch', () => {
  const parsed = helpers.parseScreenshotResult(result({ status: 'ERROR', fields: null, evaluation: { decision: 'MISMATCH', reasons: ['识别失败，按不符合处理'], warnings: [] }, errorCode: 'VISION_TIMEOUT', errorMessage: 'AI服务超时' }))
  assert.equal(parsed.status, 'ERROR')
  assert.equal(parsed.evaluation.decision, 'MISMATCH')
  assert.equal(parsed.fields, null)
  assert.throws(() => helpers.parseScreenshotResult(result({ status: 'ERROR' })))
  assert.throws(() => helpers.parseScreenshotResult(result({ evaluation: { decision: 'ERROR', reasons: [], warnings: [] } })))
  assert.throws(() => helpers.parseScreenshotResult(result({ status: 'ERROR', evaluation: { decision: 'ERROR', reasons: [], warnings: [] } })))
  assert.throws(() => helpers.parseScreenshotResult(result({ status: 'ERROR', evaluation: { decision: 'MISMATCH', reasons: [], warnings: [] } })))
  for (const decision of ['MATCH', 'REVIEW']) {
    assert.throws(() => helpers.parseScreenshotResult(result({ status: 'ERROR', fields: null, evaluation: { decision, reasons: [], warnings: [] } })))
  }
  assert.throws(() => helpers.parseScreenshotResult(result({ fields: null })))
})

test('legacy ERROR decision remains readable and explicitly describes mismatch treatment', () => {
  const parsed = helpers.parseScreenshotResult(result({ status: 'ERROR', fields: null, evaluation: { decision: 'ERROR', reasons: ['旧版识别失败'], warnings: [] } }))
  assert.equal(parsed.status, 'ERROR')
  assert.equal(parsed.fields, null)
  assert.equal(helpers.screenshotDecisionLabels.ERROR, '识别失败，按不符合处理')
})

test('clear mismatches and unusable or uncertain extraction remain mismatch results', () => {
  for (const reason of ['薪资低于要求', '无法识别岗位内容', '薪资字段相互矛盾', '存在无法确认的识别字段']) {
    const parsed = helpers.parseScreenshotResult(result({ evaluation: { decision: 'MISMATCH', reasons: [reason], warnings: [] } }))
    assert.equal(parsed.status, 'COMPLETE')
    assert.equal(parsed.evaluation.decision, 'MISMATCH')
  }
})

test('invalid field data and unexpected decisions fail closed', () => {
  for (const change of [{ salaryUnit: 'YEAR' }, { salaryMin: '150' }, { graduationYears: [9999] }, { skills: {} }, { daysPerWeek: -1 }]) {
    assert.throws(() => helpers.parseScreenshotResult(result({ fields: fields(change) })))
  }
  assert.throws(() => helpers.parseScreenshotResult(result({ evaluation: { decision: 'SEND', reasons: [], warnings: [] } })))
})

test('page includes explicit per-upload consent and protected POST headers without storing secrets', () => {
  const page = fs.readFileSync(path.join(__dirname, '..', 'app', 'screenshot-analysis', 'page.tsx'), 'utf8')
  assert.match(page, /form\.append\('consent', 'true'\)/)
  assert.match(page, /headers: OFFLINE_SCREENSHOT_HEADER/)
  assert.match(page, /'Content-Type': 'application\/json', \.\.\.OFFLINE_SCREENSHOT_HEADER/)
  assert.match(page, /type="password"/)
  assert.equal(/localStorage|sessionStorage|console\./.test(page), false)
  assert.equal(/\/api\/(boss|liepin|51job|zhilian)\//.test(page), false)
  assert.match(page, /URL\.revokeObjectURL/)
  assert.match(page, /if \(busy\.current\) return/)
  assert.match(page, /字段缺失按符合处理，不代表缺失内容已经得到事实确认/)
  assert.match(page, /按不符合处理并保存错误结果/)
  assert.equal(page.includes('没写毕业届次时会保留为待确认'), false)
  assert.match(page, /type="file" multiple/)
  assert.match(page, /screenshots\.forEach\(\(\{ file \}\) => form\.append\('files', file\)\)/)
  assert.equal(/form\.append\('file',/.test(page), false)
  assert.match(page, /validateScreenshotFiles\(\[\.\.\.screenshots\.map\(item => item\.file\), \.\.\.selected\]\)/)
  assert.match(page, /const updateScreenshots = \(next: SelectedScreenshot\[\]\) => \{[\s\S]*?setConsent\(false\)/)
  for (const action of ['上移', '下移', '移除']) assert.equal(page.includes(`aria-label={\`${action}第`), true)
  assert.match(page, /上下屏可以重叠，模型会联合提取为一份岗位信息/)
  assert.match(page, /result\.sources \?\? \[/)
  assert.match(page, /合并识别的岗位信息/)
})
