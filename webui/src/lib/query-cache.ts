/**
 * 只读查询的进程内缓存 (stale-while-revalidate)。
 *
 * 它解的是三个同源症状:
 *
 *  1. **切页面闪一下**。原实现每次挂载都从 {status:'loading', data:null} 起步, 于是每翻一页整屏先变骨架屏,
 *     等一次往返回来再长出内容 —— 而那份内容上一秒还在屏幕上。命中缓存后首帧直接是 ready, 一格骨架都不画。
 *  2. **同一份数据被要两遍**。首页与外壳都要 player.profile, 原实现是两个互不相识的 hook 实例, 发两次
 *     往返、打六次 SQLite。同键在途合并 (inflight) 之后是一次。
 *  3. **来回翻页反复打服务端**。15 秒新鲜期内重新挂载<b>一个请求都不发</b>, 这是"服务器压力"这一问的正解:
 *     翻回上一页的代价从"一次往返"降到零。
 *
 * 三条刻意的取舍:
 *
 *  - **只活在内存里, 不落 localStorage**。余额、挂单、冷却这些数字跨会话复用的价值是负的: 玩家关掉游戏再进来,
 *    第一帧看到的是上一局的钱包余额, 而它看起来与真实数据毫无区别。内存缓存的生命周期恰好等于 SPA 的生命
 *    周期 (关面板只是隐藏 MC 的 Screen, 页面原样活着), 这正是"切页面不要闪"需要的那个长度, 不多也不少。
 *  - **后台重查失败 = 整条降为 error, 不留旧数据**。留着旧数据配一个小角标更好看, 但那意味着屏幕上的金额可能
 *    是三分钟前的而没人知道 —— 与全库"异常必须痛"同一条纪律。
 *  - **写操作后一律全量作废** (见 lib/refresh.ts), 不做"哪个写操作影响哪些读"的精细映射表: 那张表漏一条的
 *    症状是某个数字偶尔不刷新, 极难复现也极难归因。
 */

import { useCallback, useEffect, useRef, useSyncExternalStore } from 'react'
import { currentRefreshRevision, subscribeRefresh } from './refresh'

/**
 * 新鲜期。15 秒内重新挂载不再请求。
 *
 * 取值理由: 它必须短于玩家"翻出去看一眼再翻回来"的耐心 (那种来回是本缓存要吃掉的主要负载), 又必须长到让
 * 一屏数据在同一次浏览里保持自洽。真正会变的数据另有两条更强的刷新路径压在上面 —— 写操作后的全量作废,
 * 与 hooks/use-live-updates.ts 里按 action 定的轮询, 故这个数不承担"数据及时性"的责任, 只承担"别白打服务端"。
 */
const FRESH_WINDOW_MS = 15_000

/**
 * 缓存条目上限。超出即逐出最久未被读到的、且当前无人订阅的条目。
 *
 * 需要它是因为一部分键带入参 (market.baseValue 按 itemId、player.itemDetail 按 slot、market.list 按筛选组合),
 * 一次长时间浏览能造出几百个键。SPA 不重载, 于是"永不淘汰"等于慢性内存泄漏。
 */
const MAX_ENTRIES = 200

export type QueryState<T> =
  | { status: 'loading'; data: T | null; error: null; refreshing: boolean }
  | { status: 'ready'; data: T; error: null; refreshing: boolean }
  | { status: 'error'; data: null; error: Error; refreshing: boolean }

interface QueryRecord {
  /**
   * 不可变快照。useSyncExternalStore 要求 getSnapshot 在无变化时返回同一个引用 —— 每次新建对象会让 React
   * 判定"外部状态一直在变"从而无限重渲染。故所有状态变更都是整体换掉这个字段。
   */
  snapshot: QueryState<unknown>
  /** 这份 data 是在哪个作废版本号下取到的; 与当前版本不等即为过期。 */
  revision: number
  /** 取到的时刻 (epoch ms)。0 = 还没有成功取过。 */
  fetchedAt: number
  /** 最近一次被读到的时刻, 仅用于淘汰排序。 */
  touchedAt: number
  /** 在途请求 —— 同键并发只发一次, 后来者共用这一个 Promise。 */
  inflight: Promise<unknown> | null
  listeners: Set<() => void>
}

const INITIAL: QueryState<unknown> = { status: 'loading', data: null, error: null, refreshing: true }

const records = new Map<string, QueryRecord>()

function now(): number {
  return Date.now()
}

function recordFor(key: string): QueryRecord {
  const existing = records.get(key)
  if (existing !== undefined) {
    existing.touchedAt = now()
    return existing
  }
  const created: QueryRecord = {
    snapshot: INITIAL,
    revision: -1,
    fetchedAt: 0,
    touchedAt: now(),
    inflight: null,
    listeners: new Set(),
  }
  records.set(key, created)
  return created
}

/**
 * 淘汰最久未读且无人订阅的条目。
 *
 * 有订阅者的条目一律不动: 它正挂在某个屏幕上, 淘汰掉等于让那个组件下一帧退回骨架屏并重新请求。
 * 全都有订阅者时不淘汰 —— 同屏能挂几百个查询本身就是设计事故, 靠缓存淘汰掩盖它只会让问题更难发现。
 *
 * 调用点刻意<b>只在取数完成时</b>, 不在 recordFor 里: getSnapshot 是在 React 的渲染期被调用的, 而那时
 * 新组件的 subscribe 还没跑 (listeners 仍是空集)。在渲染期淘汰就可能把同一批里刚建好、尚未登记订阅者的
 * 条目删掉, 于是该组件在 render 与 subscribe 之间读到两个不同的快照对象 —— React 会因此判定外部状态在
 * 渲染中变过并重渲一轮。症状轻但极难归因, 而把淘汰挪出渲染期就彻底没有这条路。
 */
function evictIfNeeded(): void {
  if (records.size <= MAX_ENTRIES) {
    return
  }
  const evictable = [...records.entries()]
    .filter(([, record]) => record.listeners.size === 0 && record.inflight === null)
    .sort((left, right) => left[1].touchedAt - right[1].touchedAt)
  const overflow = records.size - MAX_ENTRIES
  for (let index = 0; index < overflow && index < evictable.length; index += 1) {
    const entry = evictable[index]
    if (entry !== undefined) {
      records.delete(entry[0])
    }
  }
}

function publish(record: QueryRecord, snapshot: QueryState<unknown>): void {
  record.snapshot = snapshot
  for (const listener of record.listeners) {
    listener()
  }
}

function toError(value: unknown): Error {
  return value instanceof Error ? value : new Error(String(value))
}

/**
 * 发起一次取数 (若同键已有在途请求则直接复用)。
 *
 * 重查期间**保住上一份数据**并只把 refreshing 置真, 不退回 loading: 退回去就是"数字明明没变也闪一下"。
 * 首次加载时 data 本来就是 null, 调用方原有的 "status !== 'ready' 就画骨架" 写法不受影响。
 */
function startFetch(key: string, fetcher: () => Promise<unknown>): Promise<unknown> {
  const record = recordFor(key)
  if (record.inflight !== null) {
    return record.inflight
  }
  const previous = record.snapshot
  publish(record, previous.status === 'ready'
    ? { ...previous, refreshing: true }
    : { status: 'loading', data: previous.data, error: null, refreshing: true })

  const revisionAtStart = currentRefreshRevision()
  const request = fetcher()
    .then((data) => {
      record.inflight = null
      record.revision = revisionAtStart
      record.fetchedAt = now()
      publish(record, { status: 'ready', data, error: null, refreshing: false })
      evictIfNeeded()
      return data
    })
    .catch((error: unknown) => {
      record.inflight = null
      // 失败不保留旧数据 (见文件头第二条取舍): 屏幕上的金额宁可显示为错误, 也不能是三分钟前的且无人知晓。
      record.revision = revisionAtStart
      record.fetchedAt = 0
      publish(record, { status: 'error', data: null, error: toError(error), refreshing: false })
      throw error
    })
  record.inflight = request
  // 本条链只为驱动缓存状态, 真正的 reject 已经通过 publish 落进快照交给了组件; 不吞异常, 只是不让它
  // 变成无人接管的 unhandledrejection (调用方拿到的是快照里的 error, 不是这个 Promise)。
  request.catch(() => undefined)
  return request
}

/** 该键当前是否需要重查 (无数据 / 作废版本推进过 / 超出新鲜期)。 */
function isStale(record: QueryRecord): boolean {
  if (record.snapshot.status !== 'ready') {
    return true
  }
  if (record.revision !== currentRefreshRevision()) {
    return true
  }
  return now() - record.fetchedAt > FRESH_WINDOW_MS
}

/**
 * 订阅一个缓存查询。key 必须由 action 名与入参签名合成 —— 同 action 不同入参是两份数据。
 *
 * fetcher 收在 ref 里而非依赖数组: 调用方每次渲染都会新建这个闭包, 放进依赖会让 effect 每帧重跑。
 * 真要发请求时取最新那个即可 (它闭包住的 payload 与 key 同源, 不会错配)。
 */
export function useCachedQuery<T>(key: string, fetcher: () => Promise<T>): QueryState<T> & {
  reload: () => void
} {
  const fetcherRef = useRef(fetcher)
  fetcherRef.current = fetcher

  const subscribe = useCallback((onStoreChange: () => void) => {
    const record = recordFor(key)
    record.listeners.add(onStoreChange)
    return () => {
      record.listeners.delete(onStoreChange)
    }
  }, [key])

  const getSnapshot = useCallback(() => recordFor(key).snapshot, [key])
  const snapshot = useSyncExternalStore(subscribe, getSnapshot, getSnapshot)

  // 作废版本号本身也要订阅: 别处的写操作推高它之后, 本条挂载中的查询必须自己动起来重查。
  const revision = useSyncExternalStore(subscribeRefresh, currentRefreshRevision, currentRefreshRevision)

  useEffect(() => {
    if (isStale(recordFor(key))) {
      void startFetch(key, () => fetcherRef.current())
    }
  }, [key, revision])

  const reload = useCallback(() => {
    void startFetch(key, () => fetcherRef.current())
  }, [key])

  return { ...(snapshot as QueryState<T>), reload }
}

/**
 * 不经 React 直接预热一个键 (导航悬停预取用)。已新鲜则什么都不做, 故重复调用是廉价的。
 *
 * 刻意不返回 Promise: 调用方是"鼠标划过侧栏"这种事件, 它既不等结果也无处处理失败 —— 预取失败的正确后果是
 * 玩家真的点进去时走正常的加载路径, 而不是弹一个"预取失败"。失败照旧落进该键的缓存快照, 一条不吞。
 */
export function prefetchQuery(key: string, fetcher: () => Promise<unknown>): void {
  if (isStale(recordFor(key))) {
    void startFetch(key, fetcher)
  }
}

/** 当前缓存条目数 (诊断用: 装进游戏后没有 devtools, 这个数是判断有没有键爆炸的唯一窗口)。 */
export function cachedQueryCount(): number {
  return records.size
}
