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
import type { TitleCustomRule, TitleCustomViolation } from './types'

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
      if (rule === 'POINT_SHOP_BOUND') {
        return '成就点商店兑换的物品已绑定, 不能在市场挂单'
      }
      // 未知 rule (服务端加了新分支而前端没跟上) 退回不带参那句, 不把机器码顶给玩家看。
      return null
    },
  },
  STORE_FAILED: {
    text: '数据库读写失败, 本次没有任何改动',
  },
  // ---- 成就点商店与奖励领取 (achievement.*) ----
  POINTS_INSUFFICIENT: {
    text: '成就点不足',
    withParams: (params) => {
      const price = required(params, 'price')
      const balance = required(params, 'balance')
      return price === null || balance === null ? null : `成就点不足: 需要 ${price}, 当前只有 ${balance}`
    },
  },
  GOODS_LIMIT_REACHED: {
    text: '已达每人限购',
    withParams: (params) => {
      // 称号类商品而玩家已经拥有该称号 (比如管理员发过): 说"已拥有"比说"限购 1 件"更贴近事实。
      if (params.reason === 'TITLE_OWNED') {
        return '你已经拥有这个称号, 不用再兑换'
      }
      const limit = required(params, 'limit')
      return limit === null ? null : `已达每人限购 (${limit} 件)`
    },
  },
  GOODS_UNKNOWN: {
    text: '商品不存在或已下架',
  },
  GOODS_REQUIREMENT_UNMET: {
    // advancementId 不进文案: 商品卡片上已经写着需要哪个成就 (隐藏成就还刻意不写名字)。
    text: '还没有获得兑换这件商品所需的成就',
  },
  INVENTORY_FULL: {
    text: '背包已满, 没有扣成就点; 腾出空位后再兑换',
  },
  REWARD_ALREADY_CLAIMED: {
    text: '这条奖励已经领取过了',
  },
  REWARD_NONE_PENDING: {
    text: '没有待领取的奖励',
  },
  REWARD_TITLE_UNAVAILABLE: {
    text: '奖励附带的称号暂时无法发放, 本次领取已全部撤销',
    withParams: (params) =>
      params.scope === 'all'
        ? '有一条奖励附带的称号暂时无法发放, 这次"全部领取"已全部撤销; 其余奖励不受影响, 可以逐条领取'
        : null,
  },
  // ---- 称号 (title.*) ----
  TITLE_NOT_OWNED: {
    text: '你还没有这个称号',
  },
  TITLE_UNKNOWN: {
    text: '称号不存在或已下架',
  },
  CUSTOM_TITLE_NOT_SPONSOR: {
    text: '你没有有效的赞助资格, 不能使用专属称号',
  },
  CUSTOM_TITLE_SELF_SERVICE_DISABLED: {
    text: '专属称号目前由管理员设置: 预览满意后, 复制参数发给管理员',
  },
  CUSTOM_TITLE_LOCKED: {
    text: '你的专属称号已被管理员锁定, 暂时不能修改',
  },
  CUSTOM_TITLE_ON_COOLDOWN: {
    text: '专属称号修改冷却中',
    withParams: (params) => {
      const nextEditAt = required(params, 'nextEditAt')
      if (nextEditAt === null || !Number.isFinite(Number(nextEditAt))) {
        return null
      }
      return `专属称号修改冷却中, 下次可修改时间: ${new Date(Number(nextEditAt)).toLocaleString('zh-CN', { hour12: false })}`
    },
  },
  CUSTOM_TITLE_INVALID: {
    text: '专属称号不合格',
    withParams: (params) => {
      const rules = required(params, 'rules')
      if (rules === null) {
        return null
      }
      const reasons = rules.split(',').map((rule) => (isCustomRule(rule) ? CUSTOM_RULE_TEXT[rule].text : rule))
      return `专属称号不合格: ${reasons.join('; ')}`
    },
  },
}

/**
 * 专属称号校验规则 -> 中文 (Title_System_DesignSpec 13.3)。措辞与服务端语言表 title.miningdim.custom.invalid.*
 * 的中文逐条对应; text 是不带参的一句 (CUSTOM_TITLE_INVALID 只给规则名时用), withArgs 按 args 的顺序填占位符,
 * 缺参时返回 null 退回 text。
 */
const CUSTOM_RULE_TEXT: Readonly<
  Record<TitleCustomRule, { text: string; withArgs?: (args: readonly string[]) => string | null }>
> = {
  EMPTY: { text: '称号文字不能为空' },
  TOO_LONG: {
    text: '总长度超出上限 (外框与符号也算在内)',
    withArgs: (args) =>
      args.length < 2 ? null : `总长 ${String(args[0])} 个字符, 最多 ${String(args[1])} 个 (外框与符号也算在内)`,
  },
  CONTENT_MISSING: { text: '至少要有 1 个内容字符 (汉字、假名、字母或数字)' },
  CONTENT_TOO_MANY: {
    text: '内容字符 (汉字、假名、字母、数字) 超出上限',
    withArgs: (args) =>
      args.length < 2
        ? null
        : `内容字符 (汉字、假名、字母、数字) 有 ${String(args[0])} 个, 最多 ${String(args[1])} 个`,
  },
  FORMAT_CODE: {
    text: '含格式码 (U+00A7), 不能使用',
    withArgs: (args) => (args.length < 1 ? null : `第 ${String(args[0])} 个字符是格式码 (U+00A7), 不能使用`),
  },
  INVISIBLE_CHAR: {
    text: '含换行、控制字符或零宽字符, 不能使用',
    withArgs: (args) =>
      args.length < 2
        ? null
        : `第 ${String(args[0])} 个字符是换行、控制字符或零宽字符 (U+${String(args[1])}), 不能使用`,
  },
  PRIVATE_USE: {
    text: '含私用区字符, 不能使用',
    withArgs: (args) =>
      args.length < 2 ? null : `第 ${String(args[0])} 个字符是私用区字符 (U+${String(args[1])}), 不能使用`,
  },
  EMOJI: {
    text: '含 emoji, 原版字体显示不了',
    withArgs: (args) =>
      args.length < 2 ? null : `第 ${String(args[0])} 个字符是 emoji (U+${String(args[1])}), 原版字体显示不了`,
  },
  CHAR_NOT_ALLOWED: {
    text: '含不在允许范围内的字符',
    withArgs: (args) =>
      args.length < 2 ? null : `第 ${String(args[0])} 个字符「${String(args[1])}」不在允许范围内`,
  },
  SPACE_EDGE: { text: '开头和结尾不能是空格' },
  SPACE_DOUBLE: {
    text: '出现了连续空格',
    withArgs: (args) => (args.length < 1 ? null : `第 ${String(args[0])} 个字符处出现了连续空格`),
  },
  BANNED_WORD: {
    text: '含违禁词',
    withArgs: (args) => (args.length < 1 ? null : `含违禁词「${String(args[0])}」`),
  },
  COLOR_FORMAT: {
    text: '颜色写法不对, 应为 #RRGGBB',
    withArgs: (args) => (args.length < 1 ? null : `颜色 ${String(args[0])} 写法不对, 应为 #RRGGBB`),
  },
  COLOR_COUNT: {
    text: '颜色要写 1 个 (单色) 或 2~3 个 (渐变)',
    withArgs: (args) =>
      args.length < 1 ? null : `颜色要写 1 个 (单色) 或 2~3 个 (渐变), 现在是 ${String(args[0])} 个`,
  },
  COLOR_TOO_DARK: {
    text: '颜色太暗',
    withArgs: (args) =>
      args.length < 3
        ? null
        : `颜色 ${String(args[0])} 太暗 (相对亮度 ${String(args[1])}, 最低 ${String(args[2])})`,
  },
  GRADIENT_TOO_DARK: {
    text: '渐变的过渡色太暗, 请换更亮或色相更接近的色标',
    withArgs: (args) =>
      args.length < 4
        ? null
        : `渐变到第 ${String(args[0])} 个字符时过渡成 ${String(args[1])}, 太暗 (相对亮度 ${String(args[2])}, 最低 ${String(args[3])}); 请换更亮或色相更接近的色标`,
  },
}

function isCustomRule(rule: string): rule is TitleCustomRule {
  return Object.hasOwn(CUSTOM_RULE_TEXT, rule)
}

/** 专属称号的一条不合格项 (title.customPreview 回执的 violations) 的中文说明。 */
export function customTitleViolationText(violation: TitleCustomViolation): string {
  // rule 来自服务端: 前端比服务端旧时可能收到不认识的规则名, 原样带出而不是编一句话 (与本文件的回退纪律一致)。
  if (!isCustomRule(violation.rule)) {
    return String(violation.rule)
  }
  const entry = CUSTOM_RULE_TEXT[violation.rule]
  const withArgs = entry.withArgs?.(violation.args) ?? null
  return withArgs ?? entry.text
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
