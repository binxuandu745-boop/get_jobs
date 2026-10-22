const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')

function loadTs(file, dependencies, globals = {}) {
  const source = fs.readFileSync(path.join(__dirname, '..', 'lib', file), 'utf8')
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS } }).outputText
  const exports = {}
  vm.runInNewContext(code, { exports, require: name => dependencies[name], ...globals }, { filename: file })
  return exports
}

const parser = loadTs('liepin-task-status.ts', {})
const snapshot = overrides => ({ success: true, isRunning: false, starting: false, paused: false, ...overrides })
const drain = async () => { for (let i = 0; i < 20; i++) await Promise.resolve() }

function mountHook(fetcher) {
  const state = []
  const effects = []
  const timers = new Map()
  const listeners = new Map()
  let nextTimer = 1
  const react = {
    useState(initial) { const index = state.length; state.push(initial); return [initial, value => { state[index] = value }] },
    useRef: current => ({ current }),
    useCallback: fn => fn,
    useEffect: fn => effects.push(fn),
  }
  const surface = name => ({
    visibilityState: 'visible',
    addEventListener: (event, handler) => listeners.set(`${name}:${event}`, handler),
    removeEventListener: event => listeners.delete(`${name}:${event}`),
  })
  const loaded = loadTs('use-liepin-task-status.ts', { react, './api': { API_BASE: 'http://local.test' }, './liepin-task-status': parser }, {
    fetch: fetcher,
    AbortController,
    setTimeout: (callback, delay) => { const id = nextTimer++; timers.set(id, { callback, delay }); return id },
    clearTimeout: id => timers.delete(id),
    window: surface('window'),
    document: surface('document'),
  })
  const hook = loaded.useLiepinTaskStatus()
  const cleanups = effects.map(effect => effect())
  return {
    hook, state, timers, listeners,
    unmount: () => cleanups.forEach(cleanup => cleanup?.()),
    tickDelay: delay => {
      const found = [...timers].find(([, timer]) => timer.delay === delay)
      assert.ok(found, `timer with delay ${delay} must be scheduled`)
      timers.delete(found[0]); found[1].callback()
    },
    tickPoll: () => {
      const found = [...timers].find(([, timer]) => timer.delay === 2000)
      assert.ok(found, 'next local status poll must be scheduled')
      timers.delete(found[0]); found[1].callback()
    },
  }
}

const response = body => ({ ok: true, json: async () => body })

test('strict status parser supports starting, pause reason and legacy missing starting', () => {
  const parsed = parser.parseLiepinTaskStatus(snapshot({ paused: true, isRunning: true, pauseReason: '  COMMUNICATION_UNCONFIRMED  ' }))
  assert.equal(parsed.pauseReason, 'COMMUNICATION_UNCONFIRMED')
  assert.equal(parser.liepinTaskStatusLabel(parsed, false), '已暂停')
  assert.equal(parser.liepinTaskStatusLabel(parsed, true), '状态未知')
  assert.equal(parser.liepinTaskStatusLabel(parser.parseLiepinTaskStatus(snapshot({ starting: true })), false), '启动中')
  assert.equal(parser.parseLiepinTaskStatus({ success: true, isRunning: false, paused: false }).starting, false)
  for (const invalid of [null, {}, { success: false }, snapshot({ paused: undefined }), snapshot({ isRunning: 'false' }), snapshot({ starting: 'true' })]) {
    assert.throws(() => parser.parseLiepinTaskStatus(invalid))
  }
})

test('periodic local GET updates running to paused and never submits automatically', async () => {
  const calls = []
  const bodies = [snapshot({ isRunning: true }), snapshot({ paused: true, pauseReason: '请核对聊天记录' })]
  const mounted = mountHook(async (url, options) => { calls.push({ url, options }); return response(bodies.shift()) })
  await drain()
  assert.equal(mounted.state[0].isRunning, true)
  mounted.tickPoll()
  await drain()
  assert.equal(mounted.state[0].paused, true)
  assert.equal(mounted.state[0].pauseReason, '请核对聊天记录')
  assert.equal(calls.length, 2)
  assert.ok(calls.every(call => call.url.endsWith('/api/liepin/status') && !call.options.method))
  mounted.unmount()
  assert.equal(mounted.timers.size, 0)
})

test('read failure marks unknown but preserves the previous pause reason', async () => {
  let fail = false
  const mounted = mountHook(async () => { if (fail) throw new Error('offline'); return response(snapshot({ paused: true, pauseReason: '待核对' })) })
  await drain()
  fail = true
  mounted.tickPoll()
  await drain()
  assert.equal(mounted.state[0].pauseReason, '待核对')
  assert.ok(mounted.state[1].includes('无法确认'))
  mounted.unmount()
})

test('concurrent refreshes coalesce and unmount aborts the pending read', async () => {
  let calls = 0
  let signal
  const mounted = mountHook(async (_, options) => {
    calls++; signal = options.signal
    return new Promise((_, reject) => signal.addEventListener('abort', () => reject(new Error('aborted'))))
  })
  await drain()
  void mounted.hook.refreshStatus()
  void mounted.hook.refreshStatus()
  assert.equal(calls, 1)
  mounted.unmount()
  await drain()
  assert.equal(signal.aborted, true)
  assert.equal(mounted.timers.size, 0)
  assert.equal(mounted.listeners.size, 0)
})

test('explicit start aborts stale reads, submits once, then trusts the fresh starting status', async () => {
  const calls = []
  let releaseOld
  let oldSignal
  const mounted = mountHook(async (url, options) => {
    calls.push({ url, options })
    if (calls.length === 1) {
      oldSignal = options.signal
      return new Promise(resolve => { releaseOld = () => resolve(response(snapshot())) })
    }
    return response(options.method === 'POST' ? { success: true, message: '已接收' } : snapshot({ starting: true }))
  })
  await drain()
  const action = mounted.hook.runAction('start')
  await drain()
  assert.equal(oldSignal.aborted, true)
  assert.equal(calls.length, 1, 'action must await the cancelled read settling')
  assert.equal(await mounted.hook.runAction('start'), null, 'no duplicate action admission')
  releaseOld()
  const result = await action
  assert.equal(result.success, true)
  assert.equal(mounted.state[0].starting, true)
  assert.equal(mounted.state[0].isRunning, false)
  assert.equal(mounted.state[3], null)
  assert.equal(calls.filter(call => call.options.method === 'POST').length, 1)
  assert.ok(calls[2].url.endsWith('/api/liepin/status'))
  mounted.unmount()
})

test('page focus and visibility restoration trigger only local status refreshes', async () => {
  const calls = []
  const mounted = mountHook(async (url, options) => { calls.push({ url, options }); return response(snapshot()) })
  await drain()
  mounted.listeners.get('window:focus')()
  await drain()
  mounted.listeners.get('document:visibilitychange')()
  await drain()
  assert.equal(calls.length, 3)
  assert.ok(calls.every(call => call.url.endsWith('/api/liepin/status') && !call.options.method))
  mounted.unmount()
})

test('a five-second GET timeout marks unknown and does not clear the last running snapshot', async () => {
  const calls = []
  let signal
  const mounted = mountHook(async (url, options) => {
    calls.push({ url, options })
    if (calls.length === 1) return response(snapshot({ isRunning: true }))
    signal = options.signal
    return new Promise((_, reject) => signal.addEventListener('abort', () => reject(new Error('read timeout'))))
  })
  await drain()
  mounted.tickPoll()
  await drain()
  assert.equal(mounted.state[2], true)
  mounted.tickDelay(5000)
  await drain()
  assert.equal(signal.aborted, true)
  assert.equal(mounted.state[0].isRunning, true)
  assert.ok(mounted.state[1].includes('无法确认'))
  assert.equal(mounted.state[2], false)
  assert.ok(calls.every(call => !call.options.method && call.url.endsWith('/api/liepin/status')))
  mounted.unmount()
  assert.equal(mounted.timers.size, 0)
})

test('HTTP failures, malformed JSON and malformed status fields all remain unknown', async t => {
  const failures = [
    ['HTTP 503', async () => ({ ok: false, json: async () => snapshot() })],
    ['malformed JSON', async () => ({ ok: true, json: async () => { throw new Error('invalid JSON') } })],
    ['malformed fields', async () => response(snapshot({ isRunning: 'false' }))],
  ]
  for (const [name, fetcher] of failures) {
    await t.test(name, async () => {
      const mounted = mountHook(fetcher)
      await drain()
      assert.equal(mounted.state[0], null)
      assert.ok(mounted.state[1].includes('无法确认'))
      assert.equal(mounted.state[2], false)
      mounted.unmount()
      assert.equal(mounted.timers.size, 0)
    })
  }
})

test('a fifteen-second POST timeout refreshes status without automatically resubmitting', async () => {
  const calls = []
  let postSignal
  const mounted = mountHook(async (url, options) => {
    calls.push({ url, options })
    if (options.method === 'POST') {
      postSignal = options.signal
      return new Promise((_, reject) => postSignal.addEventListener('abort', () => reject(new Error('action timeout'))))
    }
    // The server may have accepted the timed-out POST: do not infer it failed to start.
    return response(snapshot({ starting: calls.length > 1 }))
  })
  await drain()
  const action = mounted.hook.runAction('start')
  await drain()
  assert.equal(mounted.state[3], 'start')
  mounted.tickDelay(15000)
  const result = await action
  assert.equal(postSignal.aborted, true)
  assert.equal(result.success, false)
  assert.ok(result.message.includes('不要重复提交'))
  assert.equal(mounted.state[0].starting, true)
  assert.equal(mounted.state[3], null)
  mounted.tickPoll()
  await drain()
  assert.equal(calls.filter(call => call.options.method === 'POST').length, 1)
  mounted.unmount()
})

test('POST admission stays locked until its follow-up GET finishes', async () => {
  const calls = []
  let releaseVerification
  const mounted = mountHook(async (url, options) => {
    calls.push({ url, options })
    if (options.method === 'POST') return response({ success: true, message: '停止请求已接收' })
    if (calls.length === 1) return response(snapshot({ isRunning: true }))
    return new Promise(resolve => { releaseVerification = () => resolve(response(snapshot({ paused: true }))) })
  })
  await drain()
  const action = mounted.hook.runAction('stop')
  await drain()
  assert.equal(calls.length, 3)
  assert.equal(mounted.state[3], 'stop')
  assert.equal(await mounted.hook.runAction('logout'), null)
  assert.equal(await mounted.hook.runAction('start'), null)
  assert.equal(calls.filter(call => call.options.method === 'POST').length, 1)
  releaseVerification()
  const result = await action
  assert.equal(result.success, true)
  assert.equal(mounted.state[0].paused, true)
  assert.equal(mounted.state[3], null)
  mounted.unmount()
})

test('unmount aborts a pending POST and does not poll, resubmit or publish action results', async () => {
  const calls = []
  let postSignal
  const mounted = mountHook(async (url, options) => {
    calls.push({ url, options })
    if (options.method === 'POST') {
      postSignal = options.signal
      return new Promise((_, reject) => postSignal.addEventListener('abort', () => reject(new Error('unmounted'))))
    }
    return response(snapshot())
  })
  await drain()
  const action = mounted.hook.runAction('start')
  await drain()
  mounted.unmount()
  const stateAfterUnmount = JSON.stringify(mounted.state)
  assert.equal(await action, null)
  assert.equal(postSignal.aborted, true)
  assert.equal(JSON.stringify(mounted.state), stateAfterUnmount)
  assert.equal(calls.length, 2)
  assert.equal(mounted.timers.size, 0)
  assert.equal(mounted.listeners.size, 0)
})
