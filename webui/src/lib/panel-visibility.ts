/**
 * 平板此刻在不在屏幕上 —— 全站唯一真源。
 *
 * 为什么需要它 (而不是用 document.visibilityState): 关面板只是隐藏 MC 的 Screen, 这个 SPA 与它背后那个
 * 离屏 Chromium 原样活着, 而 CEF **不知道**自己已经不被采样了 —— document.hidden 恒为 false, requestAnimationFrame
 * 照跑, 定时器照响。于是任何还在跑的定时器都会继续触发重渲染, 后台一遍遍栅格一张几百万像素的表面
 * (4K 上是 2688x1439), 而玩家此刻正在野外跑图, 那份开销纯属白烧。
 *
 * 唯一知情方是宿主, 故由它在 setScreen 前后各派一条事件 (见 WebUiClient.openScreen / onScreenClosed),
 * 这里把那两条事件收敛成一个布尔。
 *
 * 默认为**可见**: 浏览器里直接开 (pnpm dev) 时根本没有宿主, 收不到任何事件 —— 默认成不可见的话整个
 * 开发环境的倒计时全是停的, 而那正是设计评审要看的东西。
 *
 * 边界: 本模块只管"要不要继续跑定时器", 不管数据新鲜度。重新可见时的数据重拉由 panelOpened ->
 * invalidateAll 那条路负责 (见 App.tsx), 两条互不重叠。
 */

import { useSyncExternalStore } from 'react'
import { subscribeWebUiEvent } from '@/bridge/events'

let visible = true

const subscribers = new Set<() => void>()

function setVisible(next: boolean): void {
  if (next === visible) {
    return
  }
  visible = next
  for (const notify of subscribers) {
    notify()
  }
}

function subscribe(onStoreChange: () => void): () => void {
  subscribers.add(onStoreChange)
  return () => {
    subscribers.delete(onStoreChange)
  }
}

function getSnapshot(): boolean {
  return visible
}

/**
 * 接上宿主的开关面板事件, 返回卸载函数。与 installWheelNormalizer 同一档, 在 App 挂载期装一次。
 *
 * 卸载时刻意**不**把 visible 复位: 卸载只发生在整棵 React 树被拆掉的时候, 那之后没有任何消费方,
 * 复位是写给不存在的读者看的; 而 StrictMode 下的双挂载会让"卸载即复位"在 dev 里制造一次假的可见性抖动。
 */
export function installPanelVisibility(): () => void {
  const offOpened = subscribeWebUiEvent('panelOpened', () => {
    setVisible(true)
  })
  const offClosed = subscribeWebUiEvent('panelClosed', () => {
    setVisible(false)
  })
  return () => {
    offOpened()
    offClosed()
  }
}

/** 平板此刻是否可见。 */
export function usePanelVisible(): boolean {
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot)
}
