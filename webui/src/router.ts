import { useCallback, useMemo, useSyncExternalStore } from 'react'

/**
 * 单 URL + 前端路由 (决策 J4): Java 侧只持有一个 webui.url, 切面板不经 Java。
 *
 * 一处必须解释的偏离: 本实现**只读 location.hash, 运行期绝不写**。
 * 宿主的授权判定是整串 URL 精确匹配 —— WebUiBridge.onQuery 要求
 * cefBrowser.getURL() 等于 WebUiClient 本次 setAllowedPage 登记的 URL (即配置里的 webui.url)。
 * 而 CEF 的 getURL 带 fragment, 一旦页面把 hash 改成 "#/pixel-check", 整串 URL 就不再等于
 * "http://localhost:5173/", 此后所有 cefQuery 会被以 -3 拒绝 —— 表现是"界面能翻页但所有数据请求全废"。
 * 因此导航走内存状态, 不触碰 location。
 *
 * 这条约束会被后来人当成 bug "顺手修好"(改成 history.pushState 或写 location.hash 都能让地址栏跟着走,
 * 在浏览器里跑起来一切正常), 而它的代价要等装进游戏才暴露, 且症状是"所有请求被拒"而不是"路由不对"。
 * 要改这里, 前提是 Java 侧先改成忽略 fragment 的前缀匹配; 在那之前, 本文件是整个前端唯一被允许
 * 触碰 location 的地方, 而它选择不碰。
 *
 * 仍然监听 hashchange: 这样运维在 webui.url 上直接带 "#/pixel-check" 打开、或 Java 侧日后的
 * client.navigate 事件改片段时, 前端能跟随。初始路由同样取自 hash, 首屏深链因此可用。
 *
 * 后续若 Java 侧改成"忽略 fragment 的前缀匹配", 本文件是唯一需要改的地方。
 */

// ============================================================
// 路由常量 (全量预注册)
// ============================================================
//
// 全部面板的路由在地基阶段一次性登记完, 而不是各面板落地时再往表里加一行:
// 路由表是并行批次唯一都要碰的文件, 十几个 agent 各加各的必然互相冲掉。
// 预先注册之后, 面板批次只需要替换自己那个页面文件的内容, 一行路由都不用动。

export const ROUTE_HOME = '/'
export const ROUTE_MARKET = '/market'
export const ROUTE_MARKET_SELL = '/market/sell'
export const ROUTE_MARKET_MINE = '/market/mine'
export const ROUTE_MARKET_HISTORY = '/market/history'
export const ROUTE_MARKET_INBOX = '/market/inbox'
export const ROUTE_SHOP = '/shop'
export const ROUTE_JOBS = '/jobs'
export const ROUTE_JOB_DETAIL = '/jobs/:id'
export const ROUTE_MINING = '/mining'
export const ROUTE_CODEX = '/codex'
export const ROUTE_MARRIAGE = '/marriage'
export const ROUTE_CASE = '/case'
export const ROUTE_SETTINGS = '/settings'
export const ROUTE_ADMIN = '/admin'
export const ROUTE_COMPONENTS = '/components'

/**
 * 全部可匹配的路由模式, 按匹配优先级排列: 静态模式一律排在含 `:参数` 的动态模式之前。
 *
 * 当前只有 `/jobs/:id` 一条动态模式, 且它与 `/jobs` 的段数不同, 顺序其实不影响结果;
 * 但顺序一旦被当成"无所谓"的东西, 日后加一条 `/market/:listingId` 就会把 `/market/sell` 吃掉,
 * 且症状是"点挂单进了一个 id 叫 sell 的详情页", 不报错。故在这里把规矩写死。
 */
export const ROUTE_PATTERNS = [
  ROUTE_HOME,
  ROUTE_MARKET,
  ROUTE_MARKET_SELL,
  ROUTE_MARKET_MINE,
  ROUTE_MARKET_HISTORY,
  ROUTE_MARKET_INBOX,
  ROUTE_SHOP,
  ROUTE_JOBS,
  ROUTE_MINING,
  ROUTE_CODEX,
  ROUTE_MARRIAGE,
  ROUTE_CASE,
  ROUTE_SETTINGS,
  ROUTE_ADMIN,
  ROUTE_COMPONENTS,
  ROUTE_JOB_DETAIL,
] as const

export type RoutePattern = (typeof ROUTE_PATTERNS)[number]

/**
 * 每条路由的标题 —— 平板外壳内容区表头与页面自身共用这一份。
 *
 * 写成 Record<RoutePattern, string> 而不是普通对象: 加一条路由却忘了给标题, tsc 直接报缺键。
 * 标题不含"平板"二字, 因为外壳自己已经署名, 重复一遍只是占宽度。
 */
export const ROUTE_TITLES: Record<RoutePattern, string> = {
  [ROUTE_HOME]: '首页 · 个人档案',
  [ROUTE_MARKET]: '跳蚤市场 · 浏览',
  [ROUTE_MARKET_SELL]: '跳蚤市场 · 挂单',
  [ROUTE_MARKET_MINE]: '跳蚤市场 · 我的挂单',
  [ROUTE_MARKET_HISTORY]: '跳蚤市场 · 成交历史',
  [ROUTE_MARKET_INBOX]: '跳蚤市场 · 收件箱',
  [ROUTE_SHOP]: '系统商店',
  [ROUTE_JOBS]: '职业总览',
  [ROUTE_JOB_DETAIL]: '单职业详情',
  [ROUTE_MINING]: '矿洞',
  [ROUTE_CODEX]: '精英怪图鉴',
  [ROUTE_MARRIAGE]: '婚姻',
  [ROUTE_CASE]: '开箱',
  [ROUTE_SETTINGS]: '设置',
  [ROUTE_ADMIN]: '管理后台',
  [ROUTE_COMPONENTS]: '组件与配色预览',
}

/** 单职业详情的路径。参数拼接收在这里, 面板不许自己拼 `/jobs/` 前缀 —— 那样改路由要全库搜字符串。 */
export function buildJobDetailPath(jobId: string): string {
  return `${ROUTE_JOBS}/${jobId}`
}

// ============================================================
// 匹配
// ============================================================

export interface RouteMatch {
  /** 当前路径原文 (形如 "/market/sell")。 */
  readonly path: string
  /** 命中的模式; null = 未知路由。刻意不用空串代表未命中, 那会与合法模式混在同一个字符串域里。 */
  readonly pattern: RoutePattern | null
  /** 动态段取值 (形如 { id: 'miner' })。未命中或无动态段时为空对象。 */
  readonly params: Readonly<Record<string, string>>
}

const EMPTY_PARAMS: Readonly<Record<string, string>> = {}

function splitSegments(path: string): string[] {
  return path.split('/').filter((segment) => segment.length > 0)
}

/** 逐段比对; 命中返回参数表 (可能为空), 未命中返回 null —— 空参数表与未命中必须可区分。 */
function matchPattern(pattern: RoutePattern, segments: readonly string[]): Record<string, string> | null {
  const patternSegments = splitSegments(pattern)
  if (patternSegments.length !== segments.length) {
    return null
  }
  const params: Record<string, string> = {}
  for (let index = 0; index < patternSegments.length; index += 1) {
    const expected = patternSegments[index]
    const actual = segments[index]
    // noUncheckedIndexedAccess: 两者的下标类型都是 T | undefined, 长度已相等故此分支不可达, 但必须写。
    if (expected === undefined || actual === undefined) {
      return null
    }
    if (expected.startsWith(':')) {
      params[expected.slice(1)] = actual
      continue
    }
    if (expected !== actual) {
      return null
    }
  }
  return params
}

export function matchRoute(path: string): RouteMatch {
  const segments = splitSegments(path)
  for (const pattern of ROUTE_PATTERNS) {
    const params = matchPattern(pattern, segments)
    if (params !== null) {
      return { path, pattern, params }
    }
  }
  return { path, pattern: null, params: EMPTY_PARAMS }
}

// ============================================================
// 内存路由状态
// ============================================================

function normalize(hash: string): string {
  const raw = hash.startsWith('#') ? hash.slice(1) : hash
  return raw.startsWith('/') ? raw : ROUTE_HOME
}

let currentRoute = normalize(window.location.hash)

const subscribers = new Set<() => void>()

function setRoute(next: string): void {
  if (next === currentRoute) {
    return
  }
  currentRoute = next
  for (const notify of subscribers) {
    notify()
  }
}

window.addEventListener('hashchange', () => {
  setRoute(normalize(window.location.hash))
})

function subscribe(onStoreChange: () => void): () => void {
  subscribers.add(onStoreChange)
  return () => {
    subscribers.delete(onStoreChange)
  }
}

function getSnapshot(): string {
  return currentRoute
}

/** 当前路由路径 (形如 "/" 或 "/pixel-check")。 */
export function useRoute(): string {
  return useSyncExternalStore(subscribe, getSnapshot)
}

/**
 * 当前路由的匹配结果。
 * 用 useMemo 锁住引用: matchRoute 每次都新建对象, 不锁的话把它放进依赖数组的调用方会每帧重跑 effect。
 */
export function useRouteMatch(): RouteMatch {
  const path = useRoute()
  return useMemo(() => matchRoute(path), [path])
}

/** 当前路由的动态段取值。noUncheckedIndexedAccess 下取值类型是 string | undefined, 调用方必须判空。 */
export function useRouteParams(): Readonly<Record<string, string>> {
  return useRouteMatch().params
}

/** 导航到目标路由。返回稳定引用, 可直接进依赖数组。 */
export function useNavigate(): (path: string) => void {
  return useCallback((path: string) => {
    setRoute(path)
  }, [])
}
