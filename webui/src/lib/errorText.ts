/**
 * errorCode -> 玩家能读懂的一句话。
 *
 * 为什么要有这张表 (服务端明明已经回了一句中文 message): 服务端那句是给排障用的原文, 措辞随实现走
 * (且新加的 W1 三个码带 params, 占位符实参只有前端知道该怎么摆进句子)。稳定机器码才是契约, 文案是前端的事。
 *
 * 与 lib/panels.ts 的 PANEL_LOCK_TEXT **必须分开两张表**: 那边是"这个面板进不去"的锁定原因, 这边是
 * "这次调用失败了"的错误码, 两个命名空间各自会长大。合成一张之后哪天两边撞了同名的码, 症状是文案静默串号。
 *
 * 未收录的码一律回退到服务端原文 (不编"操作失败, 请重试"这类话术): 收不到的码要么是服务端加了新码而前端
 * 没跟上, 要么是通用异常路径根本没有码 —— 两种情况下服务端原文都是现场唯一的线索, 盖掉它等于把排障线索抹了。
 */

import type { WebUiBusinessError } from './bridge'
import { WebUiCallError } from './bridge'

interface ErrorCodeText {
  /** 服务端没给 params (或 params 缺必需键) 时用的那一句。 */
  readonly text: string
  /** 带占位符实参的那一句; 必需键缺席时返回 null, 由调用方退回 text。 */
  readonly withParams?: (params: Readonly<Record<string, string>>) => string | null
}

/** 取一个必需键; 缺席即返回 null, 让整句退回不带参的版本 (严禁把 undefined 直接拼进文案)。 */
function required(params: Readonly<Record<string, string>>, key: string): string | null {
  const value = params[key]
  return value === undefined ? null : value
}

const ERROR_CODE_TEXT: Readonly<Record<string, ErrorCodeText>> = {
  INVALID_REQUEST: {
    text: '请求内容不合法',
    withParams: (params) => {
      const field = required(params, 'field')
      if (field === null) {
        return null
      }
      const value = required(params, 'value')
      return value === null ? `字段 ${field} 不合法` : `字段 ${field} 不接受这个取值: ${value}`
    },
  },
  SLOT_OUT_OF_RANGE: {
    text: '槽位超出背包范围',
    withParams: (params) => {
      const slot = required(params, 'slot')
      const size = required(params, 'size')
      return slot === null || size === null ? null : `槽位 ${slot} 超出背包范围 (共 ${size} 格)`
    },
  },
  SLOT_EMPTY: {
    text: '这个槽位是空的',
    withParams: (params) => {
      const slot = required(params, 'slot')
      return slot === null ? null : `槽位 ${slot} 是空的`
    },
  },
  CASE_DISABLED: { text: '开箱系统当前不可用' },
  INSUFFICIENT_FUNDS: { text: '余额不足' },
  RATE_LIMITED: { text: '操作太快了, 稍等一下再试' },
  OPENING_REFUNDED: { text: '这次开箱已退款, 请重新开一次' },
  OPENING_ID_CONFLICT: { text: '这次开箱的编号已被占用, 请重新开一次' },
  TACZ_UNAVAILABLE: { text: '枪械模块未就绪, 暂时无法应用皮肤' },
  ASSET_NOT_OWNED: { text: '你没有这件皮肤' },
}

/**
 * 业务拒绝的中文文案; 未收录的码返回 null (调用方回退到服务端原文)。
 */
export function businessErrorText(business: WebUiBusinessError): string | null {
  // hasOwn 而非直接索引: errorCode 来自服务端, 取值 "toString" / "constructor" 时直接索引会命中原型链上的
  // Function, 后面 entry.text 拿到 undefined —— 签名说好 string | null, 却悄悄漏一个 undefined 给渲染层。
  const entry = Object.hasOwn(ERROR_CODE_TEXT, business.errorCode)
    ? ERROR_CODE_TEXT[business.errorCode]
    : undefined
  if (entry === undefined) {
    return null
  }
  if (entry.withParams === undefined || business.params === undefined) {
    return entry.text
  }
  const withParams = entry.withParams(business.params)
  return withParams === null ? entry.text : withParams
}

/**
 * 一次调用失败该显示给玩家的那句话。
 *
 * 优先级: 本表的码文案 -> 服务端原文。刻意不做"未知错误"兜底 —— 见文件头。
 */
export function callErrorText(error: Error): string {
  if (!(error instanceof WebUiCallError) || error.business === null) {
    return error.message
  }
  const text = businessErrorText(error.business)
  return text === null ? error.message : text
}
