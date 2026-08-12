import type { MiningdimQueryRequest } from './types'

/** 桥不可用 (页面不在 MCEF 宿主内) 时用的错误码。Java 侧的失败码是 0/-1/-2/-3, 这里取一个不冲突的负值。 */
export const BRIDGE_UNAVAILABLE_CODE = -100

/** 响应体不是合法 JSON 时用的错误码。属前端/服务端契约破裂, 与业务失败区分开。 */
export const BRIDGE_MALFORMED_CODE = -101

/** 宿主既没回成功也没回失败, 由本层看门狗强行了结时用的错误码 (见 WATCHDOG_MS)。 */
export const BRIDGE_ABANDONED_CODE = -102

/**
 * 看门狗时限。宿主自己有 30 秒超时 (WebUiBridge.CALLBACK_TIMEOUT_SECONDS), 正常情况下轮不到这里。
 *
 * 它存在是因为宿主有一条**回调永不触发**的真实路径: 玩家按 ESC 关界面时 WebUiBridge.onScreenClosed
 * 直接 pending.clear(), 在途请求的 CefQueryCallback 被整批丢弃; 随后到期的 expireRequest 已经找不到它,
 * 于是既不 success 也不 failure。JS 侧那个 Promise 从此永远挂着 —— 表现是提交按钮转圈到天荒地老,
 * 且不留任何错误。宿主侧的正解是关屏时逐个 failure, 但那属于 Java 客户端模块; 在前端这一侧,
 * 唯一能做且必须做的是给每次往返兜一个比宿主时限更长的死线, 让"没有回音"变成一个显式失败。
 */
const WATCHDOG_MS = 35_000

export class WebUiQueryError extends Error {
  readonly code: number
  readonly action: string

  constructor(code: number, action: string, message: string) {
    super(message)
    this.name = 'WebUiQueryError'
    this.code = code
    this.action = action
  }
}

/**
 * 向宿主发一次请求。服务端权威动作走 C2S 往返, client.* 前缀的动作由客户端就地处理, 两者对调用方同形。
 *
 * 失败一律以 reject 冒泡, 不做任何默认值兜底 —— 余额/库存一类字段回假值比报错危险得多。
 */
export function webUiQuery<T>(action: string, payload: Record<string, unknown> = {}): Promise<T> {
  const query = window.miningdimQuery
  if (query === undefined) {
    return Promise.reject(
      new WebUiQueryError(
        BRIDGE_UNAVAILABLE_CODE,
        action,
        `WebUI 桥不可用: 页面未运行在 MCEF 宿主内 (action=${action})`,
      ),
    )
  }

  return new Promise<T>((resolve, reject) => {
    // CEF 允许对同一 query 回调多次 (persistent query), 且看门狗与真回调可能擦肩而过。
    // Promise 本身对重复 settle 是静默忽略, 但看门狗必须据此决定"还要不要发 cancel", 故自己记一份状态。
    let settled = false
    let watchdog: ReturnType<typeof setTimeout> | undefined = undefined

    const finish = (): boolean => {
      if (settled) {
        return false
      }
      settled = true
      if (watchdog !== undefined) {
        clearTimeout(watchdog)
      }
      return true
    }

    const request: MiningdimQueryRequest = {
      request: JSON.stringify({ action, payload }),
      onSuccess: (response) => {
        if (!finish()) {
          return
        }
        // 回调由 CEF 异步触发, 此处抛出会变成无人接管的异常, 故转成 reject 交回调用方 —— 不是吞异常。
        let parsed: T
        try {
          parsed = JSON.parse(response) as T
        } catch (parseError) {
          reject(
            new WebUiQueryError(
              BRIDGE_MALFORMED_CODE,
              action,
              `响应不是合法 JSON (action=${action}): ${String(parseError)}`,
            ),
          )
          return
        }
        resolve(parsed)
      },
      onFailure: (errorCode, errorMessage) => {
        if (!finish()) {
          return
        }
        reject(new WebUiQueryError(errorCode, action, errorMessage))
      },
    }

    const queryId = query(request)

    if (settled) {
      // 宿主同步回调 (client.* 本地动作就走这条) 时已经了结, 再挂一个 35 秒的空定时器纯属占着不放。
      return
    }
    watchdog = setTimeout(() => {
      if (!finish()) {
        return
      }
      // 告诉宿主别再惦记这条: 命中 onQueryCanceled 后它会清掉自己那份在途登记, 不留悬挂 callback。
      const cancel = window.miningdimQueryCancel
      if (cancel !== undefined) {
        cancel(queryId)
      }
      reject(
        new WebUiQueryError(
          BRIDGE_ABANDONED_CODE,
          action,
          `宿主在 ${String(WATCHDOG_MS)}ms 内既未回成功也未回失败 (action=${action}): ` +
            '通常是界面在请求在途期间被关闭, 宿主已丢弃该回调',
        ),
      )
    }, WATCHDOG_MS)
  })
}
