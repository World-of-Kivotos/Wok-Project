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
 *
 * **开箱那一组码刻意不在本表里** (CASE_DISABLED / INSUFFICIENT_FUNDS / RATE_LIMITED / OPENING_REFUNDED /
 * OPENING_ID_CONFLICT / TACZ_UNAVAILABLE / ASSET_NOT_OWNED, 全部只由 CaseOpeningService 抛出): CasePage 与
 * AdminPage 各自展示服务端中文原文 + 括号里的机器码, 不走 callErrorText, 收进来也永远读不到 ——
 * 留一张读不到的表比没有更糟, 它看着像"已经收编了"。真要改成走本表, 得连那两页一起改, 且 CASE_DISABLED
 * 在服务端是一码两义 (运营关闭 / TaCZ 与资源包未就绪), 得先在服务端用 params 把成因拆开, 那是另一批的事。
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
  SKILL_LOCKED: {
    text: '该技能尚未解锁',
    withParams: (params) => {
      const requiredLevel = required(params, 'requiredLevel')
      const currentLevel = required(params, 'currentLevel')
      return requiredLevel === null || currentLevel === null
        ? null
        : `需要 ${requiredLevel} 级才能使用 (当前 ${currentLevel} 级)`
    },
  },
  SKILL_ON_COOLDOWN: {
    text: '技能冷却中',
    /*
     * 服务端发的是 tick, 那是它唯一有的时间量纲; 玩家看不懂 tick, 故在此换算成秒 (1 tick = 50ms)。
     * 服务端不做这层换算是对的 —— 掉刻时 tick 与真实秒不成正比, 换算属于展示决策而非权威数据。
     */
    withParams: (params) => {
      const remainingTicks = required(params, 'remainingTicks')
      if (remainingTicks === null) {
        return null
      }
      const ticks = Number(remainingTicks)
      if (!Number.isFinite(ticks)) {
        return null
      }
      return `冷却中, 还需约 ${String(Math.ceil(ticks / 20))} 秒`
    },
  },
  ECONOMY_OFFLINE: {
    // 无 params: 这条是环境故障, 玩家做什么都没用, 唯一有用的信息是"东西没少"。
    text: '经济子系统未就绪, 本次没有扣掉任何物品',
  },
  NOTHING_TO_SELL: {
    // itemId 不进文案: 前端拿它去解物品名要走 client.i18n 一次往返, 而这句话不带名字也说得清。
    text: '背包里没有可出售的作物',
  },
  QUEST_DISABLED: {
    text: '任务系统当前未启用',
  },
  ITEM_NOT_TRADABLE: {
    /*
     * 一码两用: market.place 拒绝时它是失败信封里的 errorCode (带 params.rule), market.tradable 判定为
     * 不可交易时它是回执里的 reasonCode (不带 params, 走下面的 text)。两条路径同码同文案是刻意的 ——
     * "按钮为什么是灰的"与"提交为什么被拒"在玩家心里必须是同一句话。
     */
    text: '这件物品不能在市场挂单',
    withParams: (params) => {
      const rule = required(params, 'rule')
      if (rule === 'TAROT_QUALITY_ABOVE_R') {
        return '只有最低品质(R)的塔罗牌可以挂单, 更高品质请自行合成'
      }
      if (rule === 'TAROT_IDENTITY_UNREADABLE') {
        return '这张塔罗牌的数据不完整, 无法上架'
      }
      // 未知 rule (服务端加了新分支而前端没跟上) 退回不带参那句, 不把机器码顶给玩家看。
      return null
    },
  },
}

/** 表里有没有这个码。errorCode 来自服务端, 直接索引会命中原型链 (见 businessErrorText 的说明)。 */
function lookup(errorCode: string): ErrorCodeText | undefined {
  return Object.hasOwn(ERROR_CODE_TEXT, errorCode) ? ERROR_CODE_TEXT[errorCode] : undefined
}

/**
 * 只有机器码、没有失败信封时的文案 (如 market.tradable 回执里的 reasonCode);
 * 未收录的码返回 null, 由调用方自己决定退回什么。
 */
export function errorCodeText(errorCode: string): string | null {
  const entry = lookup(errorCode)
  return entry === undefined ? null : entry.text
}

/**
 * 业务拒绝的中文文案; 未收录的码返回 null (调用方回退到服务端原文)。
 */
export function businessErrorText(business: WebUiBusinessError): string | null {
  // 走 lookup 而非直接索引: errorCode 来自服务端, 取值 "toString" / "constructor" 时直接索引会命中原型链上的
  // Function, 后面 entry.text 拿到 undefined —— 签名说好 string | null, 却悄悄漏一个 undefined 给渲染层。
  const entry = lookup(business.errorCode)
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
