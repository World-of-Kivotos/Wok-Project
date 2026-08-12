import { useCallback, useSyncExternalStore } from 'react'

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
 * 仍然监听 hashchange: 这样运维在 webui.url 上直接带 "#/pixel-check" 打开、或 Java 侧日后的
 * client.navigate 事件改片段时, 前端能跟随。初始路由同样取自 hash, 首屏深链因此可用。
 *
 * 后续若 Java 侧改成"忽略 fragment 的前缀匹配", 本文件是唯一需要改的地方。
 */

export const ROUTE_HOME = '/'
export const ROUTE_PIXEL_CHECK = '/pixel-check'

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

/** 导航到目标路由。返回稳定引用, 可直接进依赖数组。 */
export function useNavigate(): (path: string) => void {
  return useCallback((path: string) => {
    setRoute(path)
  }, [])
}
