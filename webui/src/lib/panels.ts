/**
 * hub 面板的**展示层元数据** —— 服务端刻意不下发的那三项 (route / label / iconItemId) 住在这里。
 *
 * 为什么留在前端 (决策 D2): 改文案、换图标、调路由都是纯前端发版 (不动 mod jar); 一旦服务端也存一份,
 * 就从"前端发版即生效"变成"两端同时发版才不指错路径", 而路线 A (远端托管 + 浏览器缓存) 下这种不同步
 * 检测不出来 —— 旧 mock 种子把精英怪图鉴的 route 写成 '/champion', 而 router.ts 里真实常量是
 * ROUTE_CODEX='/codex', 这就是已实测的漂移证据。服务端只权威"这个面板我现在能不能进"。
 *
 * 写成 Record<HubPanelId, T>: 服务端往 panelId 域里加一条 (契约类型随之扩), 这张表漏配即 tsc 报缺键。
 * 反过来, 服务端发来一个本表不认识的 panelId (旧前端 + 新服务端) 不会崩 —— 消费方按 id 查不到就跳过,
 * 见 HomePage 的 resolvePanel。
 *
 * route 一律取 router.ts 的常量而不是写路径字面量: 路由改名时那边改一处, 这里跟着走, 不用全库搜字符串。
 */

import type { HubPanelId } from './types'
import {
  ROUTE_ADMIN,
  ROUTE_CASE,
  ROUTE_CODEX,
  ROUTE_HOME,
  ROUTE_JOBS,
  ROUTE_MARKET,
  ROUTE_MARRIAGE,
  ROUTE_MINING,
  ROUTE_QUESTS,
  ROUTE_SETTINGS,
  ROUTE_SHOP,
} from '../router'

export interface HubPanelMeta {
  /** 磁贴上的中文名。 */
  readonly label: string
  /** 点进去的前端路由 (router.ts 常量)。 */
  readonly route: string
  /** 磁贴图标用的物品 id, 走 ItemIcon 的取图链; 取不到就落占位块。 */
  readonly iconItemId: string
}

export const HUB_PANEL_META: Record<HubPanelId, HubPanelMeta> = {
  home: { label: '个人档案', route: ROUTE_HOME, iconItemId: 'minecraft:book' },
  market: { label: '跳蚤市场', route: ROUTE_MARKET, iconItemId: 'minecraft:emerald' },
  shop: { label: '系统商店', route: ROUTE_SHOP, iconItemId: 'minecraft:chest' },
  jobs: { label: '职业', route: ROUTE_JOBS, iconItemId: 'minecraft:iron_pickaxe' },
  mining: { label: '矿洞', route: ROUTE_MINING, iconItemId: 'minecraft:deepslate' },
  quests: { label: '任务', route: ROUTE_QUESTS, iconItemId: 'minecraft:writable_book' },
  codex: { label: '精英怪图鉴', route: ROUTE_CODEX, iconItemId: 'minecraft:wither_skeleton_skull' },
  marriage: { label: '婚姻', route: ROUTE_MARRIAGE, iconItemId: 'minecraft:golden_apple' },
  case: { label: '开箱', route: ROUTE_CASE, iconItemId: 'minecraft:ender_chest' },
  settings: { label: '设置', route: ROUTE_SETTINGS, iconItemId: 'minecraft:comparator' },
  admin: { label: '管理后台', route: ROUTE_ADMIN, iconItemId: 'minecraft:command_block' },
}

/**
 * lockCode -> 玩家能读懂的一句话。
 *
 * 与 errorText.ts 的 ERROR_CODE_TEXT **必须分开两张表** (且严禁合并): lockCode 说的是"这个面板现在进不去",
 * errorCode 说的是"这次调用失败了", 两个命名空间各自会长大 (等级门/未婚 vs 余额不足/开箱冷却)。
 * 合成一张之后, 两边哪天撞了同名的码, 症状是文案静默串号 —— 玩家看到的锁定原因变成一句调用失败的话。
 */
const PANEL_LOCK_TEXT: Readonly<Record<string, string>> = {
  NOT_OP: '仅 OP 可进入',
  QUEST_DISABLED: '任务系统当前未启用',
}

/**
 * 锁定原因文案。未知码不编话术, 原样带出机器码 —— 那说明服务端加了新锁而前端还没跟上,
 * 编一句"暂不可用"会把这条真实的契约漂移伪装成正常状态。
 */
export function panelLockText(lockCode: string): string {
  // hasOwn 而非直接索引: lockCode 来自服务端, "toString" 一类的取值会经原型链取出 Function 当成文案返回。
  const text = Object.hasOwn(PANEL_LOCK_TEXT, lockCode) ? PANEL_LOCK_TEXT[lockCode] : undefined
  return text === undefined ? `暂不可进入 (${lockCode})` : text
}
