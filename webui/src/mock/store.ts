/**
 * mock 世界状态单例 —— 全部面板在"接线前"共用的那一份可变内存世界。
 *
 * 为什么不是静态 fixture: 验收判据是"操作有可见后果" —— 写操作之后 hub 首页的余额与背包格要跟着变、
 * 顶栏 OP 视图一拨三处面板一起翻。一坨常量做不到这件事, 只能做出看着像能用、点下去什么都不动的死界面。
 *
 * 与 ../lib/bridge.mock.ts 的分工 (二者并存, 互不写对方的状态):
 *   - bridge.mock 是**真契约 action** 的假后端, 挂在 lib/bridge 的 call() 后面。它是那些 action
 *     在 dev 下的唯一权威, 本文件不复制它的任何规则, 也不改它的任何字段。
 *   - 本文件是**后端还没有的那些面板**的假世界, 并额外持有真域回执的只读镜像 (mirror), 好让 hub 首页
 *     这类聚合视图不必自己再发一轮请求。镜像只由 handlers 在调完 call() 之后写入, 是单向的。
 *
 * 本轮核销 (28 条 action 落地真服) 之后, 假世界只剩系统商店 shops 一块 —— 职业/经济/婚姻/矿洞/图鉴/管理
 * 六块连同各自的种子已随对应 handler 一并删除, 它们现在整条走真桥。
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
import type { PlannedShopEntry } from './planned'
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
 * 钱包叠加层 —— 曾经唯一一处本文件敢改"真域数据"的地方, 现已到达它自己写明的销毁条件。
 *
 * 原理由: 买卡包/买戒指这类扣费当时是 planned 域动作, 钱包权威却在 bridge.mock 内部无外部写入口,
 * 不叠加就会出现"付了钱但余额纹丝不动"的错觉。这些 action 本轮全部核销成真契约, 扣款改由服务端落在
 * 那份真钱包上, 于是**本文件已无任何写入方, 两个分量恒为 0**。
 *
 * 之所以还留着结构: lib/bridge.mock 的 mockProfile 仍在读它做余额合成 (那侧本轮尚未跟进改造)。
 * 待 bridge.mock 补齐 28 条真 action 时, 应连同那处读取一并删除本字段。
 */
export interface MockWalletOverlay {
  credit: number
  azure: number
}

/**
 * 职业进度。八家职业面板本身已全部核销成真契约, 各自的假数据随之搬进 lib/bridge.mock (真契约那侧的
 * 唯一假后端), 本处不再留一份 —— 两份权威必然漂移。
 *
 * 只剩 progress 一项: 它的消费方是 bridge.mock 的 mockProfile / mockJobProgress / mockJobLevel,
 * 那侧刻意不另存一份等级, 否则外壳顶栏的 OP 视图与首页会出现"开关拨了但数字没变"的假故障。
 */
export interface MockJobState {
  progress: PlayerJobProgressEntry[]
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
  shops: PlannedShopEntry[]
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
