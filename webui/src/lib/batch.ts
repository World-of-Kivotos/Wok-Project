/**
 * 只读请求的批量合并 —— 把"一屏 N 次往返"压成一次。
 *
 * 为什么需要: 每条 action 是一个独立的 C2S 包 + 一次服务器主线程任务 + 一条 S2C 回执。平板冷启动实测 11 条
 * (外壳 4 + 首页 7, 其中 player.profile 被外壳与首页各要一遍), 翻一页再来一批。代价不在带宽而在**次数**:
 * 每次都要排一遍主线程任务队列; 而且那 11 个 Promise 各自到达, 于是同一屏的数字是逐个跳出来的 —— 玩家看到的
 * 就是"闪一下"。
 *
 * 合并的口子开在 {@link call} 内部而不是让各页面改成调批量 API: 24 个页面文件一行不用动, 且新面板天生享受。
 * 代价是 {@link BATCH_WINDOW_MS} 的合并窗口 —— 见下方取值理由。
 *
 * 三条硬边界:
 *  1. **只合并白名单内的只读 action** (BATCHABLE_ACTIONS, 与服务端 WebUiBatchAction.BATCHABLE 同源)。写操作
 *     绝不许进批: 一整批只占一个 requestId, 批内没有派发器那道"同 requestId 只执行一次"的防重放保护, 重放即
 *     二次扣款。服务端也会逐条拒 (ACTION_NOT_BATCHABLE), 两侧各有一道门。
 *  2. **服务端没有 system.batch 就永久退回单发**。远端托管路线下浏览器可能缓存新前端配旧服务端, 此时批量必须
 *     自己降级, 而不是让一屏数据全灭。
 *  3. **单条溢出改走单发**。服务端的体积守卫会把塞不进 32767 字符的条目换成 RESPONSE_TOO_LARGE 标记
 *     (见 WebUiBatchAction.assemble), 收到即对那一条重发单条 —— 这正是那个标记存在的目的。
 */

import { BRIDGE_UNAVAILABLE_CODE } from '../bridge/query'
import type { WebUiActionName } from './actions'

/**
 * 合并窗口。取 8ms (约半帧) 而不是 queueMicrotask 的"同一微任务":
 * React 把同一次 commit 里所有组件的 effect 排在同一个宏任务里, 微任务窗口能抓住它们; 但外壳的
 * revalidate 与新页面的首查常常隔着一次 commit (页面切换时外壳先重渲染, 新页面的 effect 在下一个 commit),
 * 微任务窗口会把它们切成两批。8ms 覆盖相邻两次 commit, 而这点延迟在一次 MCEF 往返 (数十毫秒) 面前不可感。
 */
const BATCH_WINDOW_MS = 8

/**
 * 单批条数上限, 必须与服务端 {@code WebUiBatchAction.MAX_CALLS} 相等。超出即切成多批 ——
 * 服务端对超限是整批拒 (BATCH_TOO_LARGE), 靠服务端拒绝来发现这个数写错了是最坏的一种发现方式。
 */
const MAX_CALLS_PER_BATCH = 24

/** 服务端把塞不下的条目换成这个码 (WebUiErrorCodes.RESPONSE_TOO_LARGE), 收到即改走单发。 */
const RESPONSE_TOO_LARGE = 'RESPONSE_TOO_LARGE'

/**
 * 服务端不允许该条 action 进批 (WebUiErrorCodes.ACTION_NOT_BATCHABLE)。
 *
 * 收到它意味着下面那张表与服务端那张不一致 —— 是<b>本文件的缺陷</b>, 不是运行期情况。处理方式是当场改走单发
 * 并喊一声: 自愈保证玩家看不到一次莫名失败, 而 console.warn 与 webui/scripts/check-frontend-contract.mjs 的
 * 两侧对表守卫保证这个缺陷不会以"某个面板偶尔慢一点"的形态永久活下去。
 */
const ACTION_NOT_BATCHABLE = 'ACTION_NOT_BATCHABLE'

/** 服务端不认识 system.batch 时的码 (WebUiErrorCodes.UNKNOWN_ACTION), 收到即永久降级。 */
const UNKNOWN_ACTION = 'UNKNOWN_ACTION'

/**
 * 允许合并的只读 action。**与服务端 WebUiBatchAction.BATCHABLE 逐条对齐**, 少一条只是那条走单发 (性能回退),
 * 多一条写 action 是资金漏洞 —— 两侧代价不对称, 所以这张表宁可漏不可多。
 *
 * 判据与服务端同一条: 该 handler 不改变任何玩家可见的持久状态 (按天生成的任务板一类幂等惰性初始化不算)。
 */
const BATCHABLE_ACTIONS: ReadonlySet<string> = new Set<WebUiActionName>([
  'admin.economy.balance',
  'admin.listItems',
  'case.state',
  'champion.codex',
  'champion.inspect',
  'economy.priceTable',
  'economy.status',
  'economy.today',
  'hub.panels',
  'job.agent.state',
  'job.blueprints',
  'job.brewer.state',
  'job.chef.state',
  'job.engineer.state',
  'job.farmer.state',
  'job.miner.state',
  'job.munitions.state',
  'job.progress',
  'job.tarot.state',
  'market.baseValue',
  'market.categories',
  'market.feePreview',
  'market.history',
  'market.list',
  'market.mine',
  'market.p2pCap',
  'market.pendingPayout',
  'market.tradable',
  'marriage.sharedInv',
  'marriage.state',
  'mining.myStatus',
  'mining.overview',
  'player.inventory',
  'player.isOp',
  'player.itemDetail',
  'player.prefs.get',
  'player.profile',
  'player.roster',
  'player.wallet',
  'quest.board',
  'system.serverStatus',
])

export function isBatchableAction(action: string): boolean {
  return BATCHABLE_ACTIONS.has(action)
}

interface PendingCall {
  readonly action: string
  readonly payload: Record<string, unknown>
  readonly resolve: (value: unknown) => void
  readonly reject: (error: unknown) => void
}

/** 一条批量回执条目。服务端保证逐条保序且与入参一一对应, 故按**下标**认领而不是按 action 名匹配。 */
interface BatchResultEntry {
  action?: unknown
  ok?: unknown
  result?: unknown
  error?: unknown
}

let queue: PendingCall[] = []
let flushTimer: ReturnType<typeof setTimeout> | undefined = undefined

/**
 * 服务端是否支持批量。一旦收到 UNKNOWN_ACTION 就永久置假 —— 不做定时重试: 这个能力在一次游戏会话里不会
 * 中途长出来 (服务端不会热装子系统), 定时重试只是每隔一会儿再白挨一次拒绝。
 */
let batchSupported = true

/** 单发通道。由 lib/bridge 在模块初始化时注入, 避免 batch <-> bridge 循环 import。 */
type DirectCall = (action: string, payload: Record<string, unknown>) => Promise<unknown>
let directCall: DirectCall | null = null

/** 失败信封 -> Error 的翻译器, 同样由 lib/bridge 注入 (它才是持有 WebUiCallError 与信封解析的那一层)。 */
type FailureTranslator = (action: string, envelopeJson: string) => Error
let translateFailure: FailureTranslator | null = null

/**
 * 由 lib/bridge 调用一次, 把单发通道与错误翻译注入本模块。
 *
 * 用注入而不是直接 import: 合并的入口在 call() 内部, 若本模块反过来 import bridge 就形成循环 ——
 * ESM 的循环 import 不报错, 只会让先求值的那一侧拿到 undefined, 症状是"某个函数偶尔不是函数"。
 */
export function installBatchTransport(direct: DirectCall, translator: FailureTranslator): void {
  directCall = direct
  translateFailure = translator
}

function requireTransport(): DirectCall {
  if (directCall === null) {
    throw new Error('批量调度器尚未注入单发通道 (installBatchTransport 未被调用)')
  }
  return directCall
}

/**
 * 把一条只读请求排进下一批。返回的 Promise 与单发完全同形 (成功回 result, 失败 reject 同一种 WebUiCallError),
 * 调用方无从分辨自己是被合并了还是单发的 —— 这是本模块的设计目标。
 */
export function enqueueBatched(action: string, payload: Record<string, unknown>): Promise<unknown> {
  if (!batchSupported) {
    return requireTransport()(action, payload)
  }
  return new Promise<unknown>((resolve, reject) => {
    queue.push({ action, payload, resolve, reject })
    if (queue.length >= MAX_CALLS_PER_BATCH) {
      // 满批立刻发, 不等窗口: 再等下去只会让这一批的第一条白等 8ms, 而它已经等不到同伴了。
      flushNow()
      return
    }
    if (flushTimer === undefined) {
      flushTimer = setTimeout(flushNow, BATCH_WINDOW_MS)
    }
  })
}

function flushNow(): void {
  if (flushTimer !== undefined) {
    clearTimeout(flushTimer)
    flushTimer = undefined
  }
  const pending = queue
  queue = []
  if (pending.length === 0) {
    return
  }
  for (let index = 0; index < pending.length; index += MAX_CALLS_PER_BATCH) {
    void sendBatch(pending.slice(index, index + MAX_CALLS_PER_BATCH))
  }
}

async function sendBatch(pending: PendingCall[]): Promise<void> {
  const direct = requireTransport()
  // 只有一条时不套信封: 省掉一层 JSON 嵌套与服务端一次数组遍历, 且失败路径少一层翻译。
  if (pending.length === 1) {
    const only = pending[0]
    if (only === undefined) {
      return
    }
    try {
      only.resolve(await direct(only.action, only.payload))
    } catch (error) {
      only.reject(error)
    }
    return
  }

  const calls = pending.map((entry) => ({ action: entry.action, payload: entry.payload }))
  let envelope: unknown
  try {
    envelope = await direct('system.batch', { calls })
  } catch (error) {
    if (isUnknownActionError(error)) {
      /*
       * 服务端是不带 system.batch 的旧构建。永久降级并把本批全部改走单发 —— 不能把这个错误直接抛给
       * 24 个调用方, 那会让一屏数据整片变成"UNKNOWN_ACTION: system.batch", 而真正的问题只是版本不齐。
       */
      batchSupported = false
      console.warn('[webui-batch] 服务端未注册 system.batch, 本会话改走单发 (前端比服务端新)')
      resendIndividually(pending)
      return
    }
    // 其余失败 (限流、桥断开、超时) 属于整批的共同失败, 逐条改单发只会把负载放大 N 倍。
    for (const entry of pending) {
      entry.reject(error)
    }
    return
  }
  dispatchEnvelope(pending, envelope)
}

/**
 * 按下标把回执派回各自的 Promise。
 *
 * 形状不符时**全批 reject 并带上实际收到的东西**, 不做任何位置猜测: 批量回执是"一次请求承载 N 个调用方"的
 * 结构, 一旦条数或形状对不上, 按名字或按残缺下标去凑等于把数据张冠李戴地发给别的面板 —— 那比整批失败危险
 * 得多 (余额显示成别人的)。
 */
function dispatchEnvelope(pending: PendingCall[], envelope: unknown): void {
  const results = extractResults(envelope)
  if (results === null || results.length !== pending.length) {
    const detail = `期望 ${String(pending.length)} 条, 实得 ${
      results === null ? '非数组' : String(results.length)
    } 条: ${JSON.stringify(envelope).slice(0, 400)}`
    const error = new Error(`system.batch 回执形状与请求不匹配 (${detail})`)
    for (const entry of pending) {
      entry.reject(error)
    }
    return
  }

  /** 两类"不是真失败"的条目 (放不下 / 不许进批) 都落到这里改走单发, 各自的诊断日志在命中处打。 */
  const resend: PendingCall[] = []
  for (let index = 0; index < pending.length; index += 1) {
    const call = pending[index]
    const entry = results[index]
    if (call === undefined || entry === undefined) {
      continue
    }
    if (entry.ok === true) {
      call.resolve(entry.result)
      continue
    }
    const envelopeJson = JSON.stringify(entry.error)
    const code = errorCodeOf(entry.error)
    if (code === RESPONSE_TOO_LARGE) {
      // 服务端说"这一条放不进本批", 改走单发 —— 这正是那个标记的用途, 不是一次真失败。
      console.info(`[webui-batch] ${call.action} 的回执超出批量体积上限, 改走单发`)
      resend.push(call)
      continue
    }
    if (code === ACTION_NOT_BATCHABLE) {
      // 两侧白名单不一致 (本文件的缺陷)。改走单发让玩家无感, 但必须喊出来 —— 见常量处的说明。
      console.warn(
        `[webui-batch] ${call.action} 在服务端不允许进批, 已改走单发; 请对齐 lib/batch.ts 与 WebUiBatchAction.java 的白名单`,
      )
      resend.push(call)
      continue
    }
    const translator = translateFailure
    call.reject(
      translator === null
        ? new Error(`${call.action}: ${envelopeJson}`)
        : translator(call.action, envelopeJson),
    )
  }
  if (resend.length > 0) {
    resendIndividually(resend)
  }
}

function resendIndividually(pending: PendingCall[]): void {
  const direct = requireTransport()
  for (const entry of pending) {
    direct(entry.action, entry.payload).then(entry.resolve, entry.reject)
  }
}

function extractResults(envelope: unknown): BatchResultEntry[] | null {
  if (typeof envelope !== 'object' || envelope === null || !('results' in envelope)) {
    return null
  }
  // `'results' in envelope` 已把类型收窄成带该键的对象, 键值仍是 unknown —— 不必再断言一次。
  const results = envelope.results
  if (!Array.isArray(results)) {
    return null
  }
  return results as BatchResultEntry[]
}

function errorCodeOf(error: unknown): string | null {
  if (typeof error !== 'object' || error === null || !('errorCode' in error)) {
    return null
  }
  const code = error.errorCode
  return typeof code === 'string' ? code : null
}

/**
 * 这次失败是不是"服务端没有 system.batch"。
 *
 * 判据取 errorCode 而不是 message 文本: 后者是英文机器串, 服务端改一个字这里就静默失效, 而失效的表现是
 * 整屏数据变成一条 UNKNOWN_ACTION 而不是自动降级。桥不可用 (-100) 不算 —— 那是根本没有宿主, 降级也没用。
 */
function isUnknownActionError(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) {
    return false
  }
  const code = (error as { code?: unknown }).code
  if (code === BRIDGE_UNAVAILABLE_CODE) {
    return false
  }
  const business = (error as { business?: unknown }).business
  return errorCodeOf(business) === UNKNOWN_ACTION
}
