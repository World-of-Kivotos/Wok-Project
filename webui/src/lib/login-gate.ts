/**
 * 服务端登录门的前端镜像: 服务端因"还没 /login"拒绝过一次请求后, 整块平板换成一张登录提示, 而不是让每个面板
 * 各自红一片"读取失败"。
 *
 * 真源在服务端 (com.miningdim.core.auth.PlayerLoginGate + WebUiServerDispatcher 的登录门): 前端从不自己判定
 * 登录与否, 只是把"收到过这两个码之一"记成一个全局状态。它只影响展示, 不拦任何请求 —— 拦截只能在服务端做,
 * 改版客户端可以无视这里的一切。
 *
 * 状态只由外壳 (components/shell/TabletShell) 清除, 四条路: 服务端推 auth.loginConfirmed (/login 落地时)、
 * 提示挂着期间的退避探测 (player.isOp 成功即说明门已放行)、平板被重新打开 (宿主派 panelOpened)、玩家点
 * "我已登录"按钮。清除之后照常重拉数据, 仍未登录的话第一条回执就会把它重新置上。刻意不做"任何请求成功就清除":
 * system.handshake 在登录前也放行, client.* 由宿主本地处理根本不经服务端, 它们的成功回执会把提示错误地摘掉 ——
 * 探测因此只认一条确定要过登录门的 action。
 */

import { useSyncExternalStore } from 'react'
import type { WebUiCallError } from './bridge'

/** 服务端 WebUiErrorCodes.NOT_LOGGED_IN: 还没通过 AccessHub 的 /login。 */
export const NOT_LOGGED_IN = 'NOT_LOGGED_IN'

/** 服务端 WebUiErrorCodes.LOGIN_CHECK_UNAVAILABLE: 登录态无从判定, /login 了也没用, 只能找管理员。 */
export const LOGIN_CHECK_UNAVAILABLE = 'LOGIN_CHECK_UNAVAILABLE'

export type LoginGateCode = typeof NOT_LOGGED_IN | typeof LOGIN_CHECK_UNAVAILABLE

let blocked: LoginGateCode | null = null
const listeners = new Set<() => void>()

function publish(next: LoginGateCode | null): void {
  if (blocked === next) {
    return
  }
  blocked = next
  for (const notify of listeners) {
    notify()
  }
}

/**
 * 看一眼一次调用失败是不是登录门拒绝, 是就置上全局状态。由 lib/bridge 在单发通道的失败出口调用 ——
 * system.batch 也走单发通道, 整批被拒时同样经过这里。
 */
export function noteLoginGateRejection(error: WebUiCallError): void {
  const code = error.business?.errorCode
  if (code === NOT_LOGGED_IN || code === LOGIN_CHECK_UNAVAILABLE) {
    publish(code)
  }
}

/** 摘掉登录提示 (之后由调用方负责重拉数据)。 */
export function clearLoginGate(): void {
  publish(null)
}

/** 当前是否被登录门挡着 (非 React 调用方用)。 */
export function currentLoginGate(): LoginGateCode | null {
  return blocked
}

function subscribe(onStoreChange: () => void): () => void {
  listeners.add(onStoreChange)
  return () => {
    listeners.delete(onStoreChange)
  }
}

/** 订阅登录门状态: null 表示没被挡。 */
export function useLoginGate(): LoginGateCode | null {
  return useSyncExternalStore(subscribe, currentLoginGate, currentLoginGate)
}
