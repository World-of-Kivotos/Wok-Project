/**
 * mock 世界状态单例 —— 全部面板在"接线前"共用的那一份可变内存世界。
 *
 * 为什么不是静态 fixture: 验收判据是"操作有可见后果" —— 挂单后它要出现在我的挂单里、卖菜后今日已售
 * 要涨、抽卡后卡池持有要变。一坨常量做不到这件事, 只能做出看着像能用、点下去什么都不动的死界面。
 *
 * 与 ../lib/bridge.mock.ts 的分工 (二者并存, 互不写对方的状态):
 *   - bridge.mock 是**真契约那 35 个 action** 的假后端, 挂在 lib/bridge 的 call() 后面。它是那些 action
 *     在 dev 下的唯一权威, 本文件不复制它的任何规则, 也不改它的任何字段。
 *   - 本文件是**后端还没有的那些面板**的假世界, 并额外持有真域回执的只读镜像 (mirror), 好让 hub 首页
 *     这类聚合视图不必自己再发一轮请求。镜像只由 handlers 在调完 call() 之后写入, 是单向的。
 *
 * 于是"同一份数据两个权威"这个经典 mock 事故在这里不成立: 钱包/背包/挂单的权威恒在 bridge.mock (接线后
 * 是真服), 本文件只存它回来的样子; 唯一的例外是 walletOverlay, 那是刻意留的、有明确销毁条件的叠加层,
 * 见该字段注释。
 *
 * 订阅机制: 一个 listener 集合, 不引第三方状态库。mutate() 会把根对象换成新引用, 因此 getWorld 可以直接
 * 喂给 useSyncExternalStore 当 getSnapshot (见 useMockWorld.ts)。嵌套对象仍是就地改 —— 面板读的是根,
 * 根一换就重渲染, 再往下做不可变更新只是徒增噪音。
 */

import type {
  MarketListing,
  PlayerInventoryItem,
  PlayerJobProgressEntry,
  WebUiWallet,
} from '../lib/types'
import type {
  PlannedAgentStateResult,
  PlannedBlueprintsResult,
  PlannedChampionCodexResult,
  PlannedChampionInspectResult,
  PlannedCurrency,
  PlannedEconomyStatusResult,
  PlannedEconomyTodayResult,
  PlannedEngineerStateResult,
  PlannedJobId,
  PlannedMarriageStateResult,
  PlannedMiningInstance,
  PlannedMiningMyStatusResult,
  PlannedMunitionsStateResult,
  PlannedPriceTableResult,
  PlannedSharedInvResult,
  PlannedShopEntry,
  PlannedTarotStateResult,
} from './planned'
import { createInitialWorld } from './seed'

/**
 * 本地玩家身份。名字与 bridge.mock 的 MOCK_PLAYER_NAME 保持一致, 否则"我的挂单"会两边对不上人。
 *
 * isOp 是设计评审用的"OP 视图"开关 (外壳顶栏那个 Toggle) 的落点, 真契约的 player.isOp / player.profile.isOp /
 * hub.panels 的 admin 门在假数据模式下都读它 (见 lib/bridge.mock.ts), 因此这里改一处、三处一起翻。
 *
 * 无 uuid 字段: 唯一消费者是已核销的 player.profile.playerUuid, 而真契约按"全库零消费者"把它砍了。
 */
export interface MockPlayerIdentity {
  name: string
  isOp: boolean
}

/**
 * 真域回执的只读镜像。null = 本会话还没拉过, 面板据此显示骨架而不是显示 0 ——
 * "还没拉到"和"真的是 0"在余额面板上是两件完全不同的事, 合成一个 0 就再也分不开了。
 */
export interface MockRealDomainMirror {
  wallet: WebUiWallet | null
  inventory: PlayerInventoryItem[] | null
  myListings: MarketListing[] | null
  /** case.state 的 ownedTotal (皮肤资产真实总数, owned 数组会被截断到 60 条)。 */
  caseOwnedTotal: number | null
  /** 最近一次镜像写入时刻; 0 = 从未写入。 */
  refreshedAt: number
}

/**
 * 钱包叠加层 —— 唯一一处本文件敢改"真域数据"的地方, 有明确的存在理由与销毁条件。
 *
 * 理由: 卖菜进账、买卡包扣费、买戒指扣费这些都是 planned 域的动作, 真服里它们由服务端直接改钱包;
 * 但 mock 阶段钱包权威在 bridge.mock 内部, 外部无写入口。若不叠加, 玩家在演示里卖完菜钱包纹丝不动,
 * 那是比数字不准更糟的错觉。
 *
 * 销毁条件: 某个 planned action 接线成真之后, 它对钱包的影响就由服务端回执带回来了, 对应的叠加调用
 * 必须一并删掉; 全部 planned 域接完时本字段整体删除。它只在 isMockActive() 为真时被计入 (见 handlers)。
 */
export interface MockWalletOverlay {
  credit: number
  azure: number
}

/**
 * 尚未核销的职业面板状态。每个字段直接就是对应 planned action 的 result 形状, handlers 只做克隆。
 *
 * 矿工/农夫/厨师/酿酒师四家已在 W3 核销成真契约, 它们的假数据随之搬进 lib/bridge.mock (真契约那侧的
 * 唯一假后端), 本处不再留一份 —— 两份权威必然漂移。progress 仍留在这里: 它的消费方是 bridge.mock 的
 * mockProfile 与仍是 planned 的 admin.job.setLevel / mining.enter 等级门, 类型换成真契约的条目形状。
 */
export interface MockJobState {
  progress: PlayerJobProgressEntry[]
  tarot: PlannedTarotStateResult
  agent: PlannedAgentStateResult
  munitions: PlannedMunitionsStateResult
  engineer: PlannedEngineerStateResult
  blueprints: PlannedBlueprintsResult
}

export interface MockEconomyState {
  status: PlannedEconomyStatusResult
  today: PlannedEconomyTodayResult
  priceTable: PlannedPriceTableResult
}

export interface MockMiningState {
  instances: PlannedMiningInstance[]
  myStatus: PlannedMiningMyStatusResult
}

export interface MockChampionState {
  codex: PlannedChampionCodexResult
  /** 可被 champion.inspect 查到的样本实体。真服按实体 id 查活体, mock 只能给几只固定的。 */
  samples: PlannedChampionInspectResult[]
}

/** 其他玩家: 求婚选人、OP 调账选目标、看配偶在线都要用 (真服这块是 A16 的缺口)。 */
export interface MockOtherPlayer {
  name: string
  uuid: string
  online: boolean
  wallet: WebUiWallet
  jobLevels: Record<PlannedJobId, number>
}

export interface MockWorld {
  /** 每次 mutate 自增。只用于让 useSyncExternalStore 感知变化, 不参与业务。 */
  readonly revision: number
  /**
   * 全部种子时间戳的基准, 模块加载时定一次。种子里所有"3 天前""还有 2 小时"都是相对它算的,
   * 于是页面存活期内时间戳不漂移, 设计稿截图可复现 (同 bridge.mock 的 NOW)。
   */
  readonly epoch: number
  player: MockPlayerIdentity
  mirror: MockRealDomainMirror
  walletOverlay: MockWalletOverlay
  jobs: MockJobState
  economy: MockEconomyState
  marriage: PlannedMarriageStateResult
  sharedInv: PlannedSharedInvResult
  mining: MockMiningState
  champion: MockChampionState
  shops: PlannedShopEntry[]
  otherPlayers: MockOtherPlayer[]
}

let world: MockWorld = createInitialWorld()

const listeners = new Set<() => void>()

/** 当前世界快照。同一 revision 内引用恒定, 可直接用作 useSyncExternalStore 的 getSnapshot。 */
export function getWorld(): MockWorld {
  return world
}

/** 订阅世界变更, 返回退订函数。 */
export function subscribeWorld(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/**
 * 就地改世界并广播。
 *
 * 遍历前先复制一份 listener 列表: 订阅者在回调里退订 (React 卸载时常见) 会改动集合本身,
 * 直接遍历原集合会漏掉后面的订阅者。
 */
export function mutateWorld(mutator: (draft: MockWorld) => void): void {
  mutator(world)
  world = { ...world, revision: world.revision + 1 }
  for (const listener of [...listeners]) {
    listener()
  }
}

/** 重置回种子状态 (dev 面板上的"重置演示数据"按钮用)。listener 不动, 订阅者会收到一次广播。 */
export function resetWorld(): void {
  const fresh = createInitialWorld()
  world = { ...fresh, revision: world.revision + 1 }
  for (const listener of [...listeners]) {
    listener()
  }
}

/**
 * 当前时刻。刻意包一层而不是全库散落 Date.now():
 * 冷却/倒计时判定与种子时间基准 (world.epoch) 必须能被一起挪动, 否则做"演示到某个时间点"的截图时,
 * 一半数据跟着走一半不跟着走。现在它就是 Date.now(), 但只有这一处需要改。
 */
export function nowMs(): number {
  return Date.now()
}

/** 深拷贝一份回执, 免得面板改到世界状态本身 (React 里最难查的一类 bug 就是有人就地改了数据源)。 */
export function cloneResult<T>(value: T): T {
  return structuredClone(value)
}

/** 按 id 取职业进度条目; 不存在即为种子数据缺陷, 直接抛而不是补一条空的。 */
export function requireJobProgress(draft: MockWorld, jobId: PlannedJobId): PlayerJobProgressEntry {
  const entry = draft.jobs.progress.find((candidate) => candidate.jobId === jobId)
  if (entry === undefined) {
    throw new Error(`mock 种子缺陷: 职业进度表里没有 ${jobId}`)
  }
  return entry
}

/** 按名字取其他玩家; 找不到时抛 —— 求婚/调账输错名字在真服也是失败, 不该静默造一个人出来。 */
export function findOtherPlayer(draft: MockWorld, name: string): MockOtherPlayer | undefined {
  const lowered = name.toLowerCase()
  return draft.otherPlayers.find((candidate) => candidate.name.toLowerCase() === lowered)
}

/** 给叠加层记一笔。currency 与 planned 契约的 PlannedCurrency 同口径。 */
export function addWalletOverlay(
  draft: MockWorld,
  currency: PlannedCurrency,
  delta: number,
): void {
  if (currency === 'CREDIT') {
    draft.walletOverlay.credit += delta
    return
  }
  draft.walletOverlay.azure += delta
}
