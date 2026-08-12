/**
 * MCEF 宿主注入到页面的桥接契约。真源是 Java 侧 com.miningdim.client.webui:
 *   - WebUiClient.QUERY_FUNCTION = "miningdimQuery" / QUERY_CANCEL_FUNCTION = "miningdimQueryCancel"
 *   - WebUiBridge.onEvent 注入时调用页面预置的 window.miningdimOnEvent
 *
 * 这三个全局都是可选的: 页面在普通浏览器里 (无 MCEF 宿主) 打开时它们不存在, 此时调用方必须显式失败,
 * 而不是回假数据 —— 服务端权威是本架构的红线, 前端伪造响应会掩盖真实的接线断裂。
 */

/** CEF message router 的 JS 侧入参 (JCEF cefQuery 信封, 仅品牌名不同)。 */
export interface MiningdimQueryRequest {
  /** JSON 字符串: {action, payload}。requestId 由 Java 客户端侧生成, 不进这层信封。 */
  request: string
  onSuccess: (response: string) => void
  onFailure: (errorCode: number, errorMessage: string) => void
}

declare global {
  interface Window {
    /** 返回值是 CEF 分配的 query id (供 miningdimQueryCancel 使用)。 */
    miningdimQuery?: (request: MiningdimQueryRequest) => number
    miningdimQueryCancel?: (queryId: number) => void
    /** 服务端事件下行入口; 由 src/bridge/events.ts 在 React 挂载时注册。 */
    miningdimOnEvent?: (eventName: string, dataJson: string) => void
  }
}
