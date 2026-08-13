/**
 * 服务端事件下行 (S2CWebUiEvent -> WebUiBridge.onEvent -> window.miningdimOnEvent)。
 * window 上那三个全局的类型声明在 ./types.ts (全局增强, 无需 import)。
 *
 * 重要现状: 服务端侧 sendWebUiEvent 目前**零业务调用方**, 这条是空管道。前端必须接住它 (否则首个
 * 生产调用方落地时事件会静默丢弃), 但任何业务逻辑都不能依赖它到达 —— 进度类数据一律轮询 (决策 J2)。
 */

type WebUiEventListener = (data: unknown) => void

const listeners = new Map<string, Set<WebUiEventListener>>()

// StrictMode 下 effect 会挂载两次, 用计数保证后一次卸载不会把仍在用的入口摘掉。
let installCount = 0

function dispatch(eventName: string, dataJson: string): void {
  // dataJson 由服务端构造并经 Gson 转义。解析失败说明契约破裂, 让它冒泡到 CEF 注入点留下堆栈,
  // 静默吞掉会让"事件面通了但数据全丢"变成无迹可查。
  const data: unknown = JSON.parse(dataJson)
  const subscribers = listeners.get(eventName)
  if (subscribers === undefined) {
    console.debug('[webui-event] 无订阅者, 事件丢弃:', eventName)
    return
  }
  /*
   * 快照后再遍历: 监听器在回调里退订 (拿到目标事件即 unsubscribe 是最自然的写法) 会就地改动这个 Set。
   *
   * 逐个 try 不是吞异常 —— 异常照样在下面以 AggregateError 抛出, 只是**先把事件送完**。
   * 直接让第一个抛出的监听器中断循环, 后果是排在它后面的订阅者永远收不到这条事件, 而事件是一次性的、
   * 不会重发: 一个订阅者的 bug 会变成另一个功能的静默数据丢失, 这比异常本身危险得多。
   */
  const failures: unknown[] = []
  for (const listener of [...subscribers]) {
    try {
      listener(data)
    } catch (listenerError) {
      failures.push(listenerError)
    }
  }
  if (failures.length > 0) {
    throw new AggregateError(failures, `事件 ${eventName} 的 ${String(failures.length)} 个监听器抛出异常`)
  }
}

/** 注册全局事件入口, 返回卸载函数。宿主注入时用 typeof 守卫, 未注册期间的事件静默丢弃。 */
export function installWebUiEventBridge(): () => void {
  installCount += 1
  window.miningdimOnEvent = dispatch
  return () => {
    installCount -= 1
    if (installCount === 0) {
      delete window.miningdimOnEvent
    }
  }
}

/** 订阅一个事件名, 返回退订函数。事件名是服务端受控常量, 前端不拼字符串。 */
export function subscribeWebUiEvent(eventName: string, listener: WebUiEventListener): () => void {
  let subscribers = listeners.get(eventName)
  if (subscribers === undefined) {
    subscribers = new Set<WebUiEventListener>()
    listeners.set(eventName, subscribers)
  }
  subscribers.add(listener)
  return () => {
    const current = listeners.get(eventName)
    if (current === undefined) {
      return
    }
    current.delete(listener)
    if (current.size === 0) {
      listeners.delete(eventName)
    }
  }
}
