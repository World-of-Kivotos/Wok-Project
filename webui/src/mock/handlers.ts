/**
 * mock 面板层的唯一调用口 —— callMock(action, payload), 与 lib/bridge 的 call(action, payload) 同签名。
 *
 * 它覆盖两种 action, 且两种走完全不同的路:
 *   1. **真契约那 65 个** (lib/actions.ts 的 SERVER_ACTIONS 63 条 + CLIENT_LOCAL_ACTIONS 2 条): 原样转调
 *      call(), 即 dev 下落 bridge.mock、装进游戏后落真服。本层一行业务规则都不加, 只在写操作成功后顺手
 *      重拉一次背包镜像 (动背包的 action), 或单纯把 world.revision 自增一下 (只动钱包不动背包的
 *      case.open/case.apply), 好让别的面板 (卖菜/买入/挂单页的背包格、顶栏余额) 立刻看见后果。
 *   2. **planned.ts 里剩下那 2 个** (shop.catalog / shop.detail): 后端还不存在 (在 WOK-ChestShop 跨仓),
 *      由 store 的内存世界回答, 带 150-400ms 人为延迟。
 *
 * 为什么要有第 1 类的转调层 (而不是让面板直接用 call):
 * 跨面板的可见后果必须有人负责。市场页买入之后 hub 首页的余额得跟着降 —— 若各页各自 call, 没人知道
 * 该通知谁; 集中到这一层, "写操作成功 -> 刷新镜像 -> 广播" 就只有一处实现。
 *
 * 接线纪律: 每接通一条 planned action, 就把它从 PLANNED_ACTIONS 与 PlannedContractMap 删掉, 于是本文件
 * 的 isPlannedAction 判定自动把它甩进第 1 类路径 —— 换言之, 接线的最后一步不需要改 callMock 的调用点,
 * 只需要改契约表。面板代码全程不动。
 *
 * 已知偏差 (mock 阶段独有, 接线即消失, 面板不得据此推断服务端行为):
 *   1. 系统商店的报价 (buyPrice / sellPrice / stock) 不复刻服务端任何定价规则, 是按等差凑出来的占位数字,
 *      只为把"只收不卖""无限库存""有比价对手/无比价对手"几种形态铺满 —— 与 bridge.mock 头部同一条纪律:
 *      漂移的假规则比没有规则更误导。比价排序也因此只是按种子顺序, 不代表真服会怎么排。
 *
 * 已随本轮核销消失的偏差 (留档, 免得有人照着旧文档再造一遍):
 *   钱包叠加层 store.walletOverlay 曾用来记 planned 域收支 (买卡包 / 买戒指 / 系统商店下单)。这三笔的
 *   前两笔已核销成真契约、由服务端直接扣款, 第三笔 (shop.buy) 按"系统商店只做浏览比价"的决策整条删除,
 *   故本层已无任何写入叠加层的地方。取而代之的是把这些真 action 登记进下面的镜像刷新表。
 */

import type { WebUiActionName } from '../lib/actions'
import type { WebUiContract } from '../lib/bridge'
import { SERVER_FAILURE_CODE, WebUiCallError, call } from '../lib/bridge'
import type {
  PlannedActionName,
  PlannedContractMap,
  PlannedPayloadOf,
  PlannedResultOf,
  PlannedShopEntry,
} from './planned'
import { PLANNED_ACTIONS } from './planned'
import type { MockWorld } from './store'
import { cloneResult, getWorld, mutateWorld, nowMs } from './store'

// ============================================================
// 契约合并 (两张表并排放, 永不合并成一张)
// ============================================================

type MockContractMap = WebUiContract & PlannedContractMap

export type MockActionName = keyof MockContractMap
export type MockPayloadOf<A extends MockActionName> = MockContractMap[A]['payload']
export type MockResultOf<A extends MockActionName> = MockContractMap[A]['result']

const PLANNED_ACTION_SET = new Set<string>(PLANNED_ACTIONS)

function isPlannedAction(action: MockActionName): action is PlannedActionName {
  return PLANNED_ACTION_SET.has(action)
}

/**
 * call 的类型擦除视图。
 *
 * 泛型 A 在派发点无法被收窄成某个具体 action, 于是没有任何写法能让 call 的实参类型与运行期的 action
 * 对上号 —— 与 bridge.mock.resolveMock 里那处 as 同源。集中成这一个具名常量而不是在每个分支各写一次
 * as, 是为了让"本文件一共有几处类型断言"这件事一眼可数。
 */
const callErased = call as unknown as (
  action: WebUiActionName,
  payload: unknown,
) => Promise<unknown>

// ============================================================
// 延迟与失败
// ============================================================

/**
 * 人为往返延迟 150-400ms 随机。
 *
 * 固定值会让 loading 态"每次都一样快", 设计上就不会去处理先后到达的竞态; 而完全没有延迟, loading 态
 * 在真机上根本没被看见过一次, 上线才发现闪一下白屏。取随机区间是为了让两者都暴露出来。
 */
function plannedLatencyMs(): number {
  return 150 + Math.floor(Math.random() * 251)
}

function sleep(ms: number): Promise<void> {
  return new Promise<void>((resolve) => {
    setTimeout(resolve, ms)
  })
}

/**
 * 业务失败。刻意复用 bridge.mock 用的同一个 WebUiCallError + SERVER_FAILURE_CODE ——
 * 面板的错误处理分支因此对两条路径完全一致, 接线时不必再改一遍 catch。
 */
function fail(action: MockActionName, message: string): WebUiCallError {
  return new WebUiCallError(action, SERVER_FAILURE_CODE, message, null)
}

// ============================================================
// 真域镜像 (第 1 类 action 的回执抄本)
// ============================================================

/**
 * 写操作之后要不要触发一次背包镜像重查。key 是真 action 名, 不要往里塞 planned 名。
 *
 * 判据: 该 action 会不会动**本人**的钱包或背包 (集合成员照旧, 一条不删)。钱包本身已不再进镜像
 * (F057: mirror.wallet 全库零读取方, 已删字段), 由各页自己的 player.profile / job.farmer.state 等
 * 聚合重查负责 —— 但那条重查链挂在 TabletShell 的 world.revision effect 上 (见下方
 * refreshInventoryMirror 与 bumpWorldRevision 的说明), 只动钱包不动背包的 action 若两张表都不进,
 * 顶栏余额就没有任何东西会把它捅醒。这张表现在只管背包那一半 —— 只动钱包不动背包的 action
 * (如 admin.economy.set) 留在表里也无妨, 多刷一次背包不会出错, 只是当次没有可见变化。
 */
const MIRROR_AFTER_INVENTORY = new Set<string>([
  'market.place',
  'market.buy',
  'market.cancel',
  // 卖菜会把作物从背包扣掉, 不刷的话 hub 首页与农夫面板的背包格会停在卖之前的样子。
  'job.farmer.sell',
  // 把卡包**实物**发进背包 (回执 itemId 就是那件卡包)。
  'job.tarot.buyPack',
  // 背包满时戒指掉在脚下, 那一次背包不变; 其余情形戒指会进背包。
  'marriage.buyRing',
  // 典礼与离婚都要收费 (两者都有 INSUFFICIENT_FUNDS 态)。离婚提交即扣费 —— 但只做"提交进公示期"这一步,
  // 共享背包按各自存入清算是到期结算时才发生, 不在这次调用里, 故这里只需刷钱包/背包的钱已扣这一半。
  'marriage.wed',
  'marriage.divorce',
  // OP 调账只动钱包, 不动背包; 留在表里见上方判据说明。
  'admin.economy.set',
])

/**
 * 写操作之后只需要顶栏重查, 不动背包的 action。case.open 扣信用点/青辉石买一次开箱机会,
 * case.apply 把已持有皮肤换到手持枪械上的持有状态 —— 两者都不进背包, 故不能塞进
 * MIRROR_AFTER_INVENTORY (那张表的刷新只查 player.inventory, 查不出钱包变化)。
 *
 * mirror.wallet / mirror.caseOwnedTotal 已随 F057 核销 (全库零读取方, 已删字段), 这两个 action
 * 因此不再需要任何专属的镜像字段 —— 唯一还欠的是 world.revision 的那一次自增, 用来叫醒
 * TabletShell.tsx 的 "世界变了就重查 profile" effect, 顶栏 Currency 才会跟着扣费后的余额走。
 */
const BUMP_REVISION_AFTER = new Set<string>(['case.open', 'case.apply'])

/**
 * 拉一遍背包并写进镜像。成功时顺带清掉上一次的失败标记。
 *
 * 必须 reject: 调用方 (FarmerPanel/BrowsePage/SellPage) 各自有用户可见的错误分支与重试按钮,
 * 这里吞掉异常就会变成"点重试没反应"。把刷新失败隔离在写操作结果之外的责任在 delegateReal
 * (不 await 这个调用), 不在这里 try/catch。
 */
export async function refreshInventoryMirror(): Promise<void> {
  const inventory = await call('player.inventory', {})
  mutateWorld((draft) => {
    draft.mirror.inventory = inventory.items
    draft.mirror.refreshedAt = nowMs()
    draft.mirror.lastError = null
  })
}

/**
 * 记录一次镜像刷新失败: console.error 打一条带前缀的诊断日志 (保留原始 error 对象, 供控制台展开完整
 * 堆栈), 并把人可读的消息写进 mirror.lastError 供顶栏显形。刷新失败不代表触发它的那次写操作失败,
 * 见 delegateReal 的红线注释。
 */
export function recordMirrorError(error: unknown): void {
  console.error('[mock-mirror] 真域背包镜像刷新失败:', error)
  mutateWorld((draft) => {
    draft.mirror.lastError = error instanceof Error ? error.message : String(error)
  })
}

/**
 * 只让 world.revision 自增, 不写任何草稿字段 —— BUMP_REVISION_AFTER 里的 action 没有专属镜像
 * 字段可写 (见该常量上方说明), 需要的只是 mutateWorld 广播这一侧的副作用。
 *
 * 同步执行、不吞异常: 与 refreshInventoryMirror 不同, 这里没有网络往返可失败, 没有 reject 的余地。
 */
function bumpWorldRevision(): void {
  mutateWorld(() => {})
}

/** 首屏预热: hub 挂载时调一次, 免得每个面板各自去发现镜像是 null。 */
export async function primeRealDomainMirror(): Promise<void> {
  await refreshInventoryMirror()
}

// ============================================================
// planned handler 用的小工具
// ============================================================

/** 系统商店比价: 同一件物品在别家店的报价 (H4 在真服是要新建的反向索引)。 */
function comparableShops(world: MockWorld, shop: PlannedShopEntry): PlannedShopEntry[] {
  return world.shops.filter(
    (candidate) => candidate.itemId === shop.itemId && candidate.shopId !== shop.shopId,
  )
}

// ============================================================
// planned handler 表
// ============================================================

/**
 * 每个 planned action 一个实现。用映射类型而不是 switch, 换来两件事:
 * 少写一条即编译失败 (键必须齐全), 且每个实现的 payload 已按自身 action 收窄, 不必逐个 as。
 */
type PlannedHandlerMap = {
  [A in PlannedActionName]: (
    payload: PlannedPayloadOf<A>,
  ) => PlannedResultOf<A> | Promise<PlannedResultOf<A>>
}

const PLANNED_HANDLERS: PlannedHandlerMap = {
  'shop.catalog': () => ({ shops: cloneResult(getWorld().shops) }),

  'shop.detail': (payload) => {
    const world = getWorld()
    const shop = world.shops.find((entry) => entry.shopId === payload.shopId)
    if (shop === undefined) {
      throw fail('shop.detail', `没有找到商店 ${payload.shopId}`)
    }
    return { shop: cloneResult(shop), comparable: cloneResult(comparableShops(world, shop)) }
  },
}

/**
 * 派发一个 planned action。
 *
 * 这里的 as 是整张表里唯一一处: 联合类型的函数不能用联合类型的实参调用 (TS 不做逐分支配对), 而每个
 * handler 的实参类型已由 PlannedHandlerMap 在定义点保证, 转换不引入运行期风险。
 */
function invokePlanned(action: PlannedActionName, payload: unknown): unknown {
  const handler = PLANNED_HANDLERS[action] as (input: unknown) => unknown
  return handler(payload)
}

async function delegateReal(action: WebUiActionName, payload: unknown): Promise<unknown> {
  const result = await callErased(action, payload)
  if (MIRROR_AFTER_INVENTORY.has(action)) {
    // 红线 (F012): 镜像刷新绝不能挂在这条 await 链上, 也绝不能包一层 try/catch 把 result 换掉 ——
    // 写操作在上一行已经生效, 刷新失败不该让调用方把已经成功的写操作误判成失败并丢弃回执。
    void refreshInventoryMirror().catch(recordMirrorError)
  } else if (BUMP_REVISION_AFTER.has(action)) {
    bumpWorldRevision()
  }
  return result
}

/**
 * 调一个 action。与 lib/bridge 的 call 同签名, 面板从头到尾只认这一个函数。
 *
 * 失败一律以 WebUiCallError 抛出, 不做任何默认值兜底 —— 与 bridge.call 同纪律:
 * 余额/库存回假值比报错危险得多。调用方只在页面级错误边界或提交按钮处收口。
 */
export async function callMock<A extends MockActionName>(
  action: A,
  payload: MockPayloadOf<A>,
): Promise<MockResultOf<A>> {
  if (isPlannedAction(action)) {
    /*
     * planned 域是前端假定契约 (planned.ts), 服务端**根本没有**对应 action。开发构建里它是设计预览的
     * 数据源; 生产构建里必须硬失败。
     *
     * 这道门不能用 isMockActive() —— 它的判据含"桥未注入", 而真客户端加载 dev server 时桥是注入的,
     * 那样会把设计预览也一并锁死。判据只看构建模式: DEV 放行 (预览), 生产拒绝 (未接线)。
     *
     * 为什么必须拒绝而不是继续回假数据: 玩家看到的商店报价与库存若来自内存假世界, 表现是
     * "一切正常但数字全错", 照着比价跑一趟才发现店根本不存在。那比抛错难查得多 —— 报错至少在界面上是可见的。
     */
    if (!import.meta.env.DEV) {
      throw new WebUiCallError(
        action,
        SERVER_FAILURE_CODE,
        `${action} 尚未接入服务端 (前端假定契约, 见 mock/planned.ts)`,
        { errorCode: 'NOT_WIRED', retrySameOpeningId: false },
      )
    }
    // 只给 planned 路径加延迟: 真域那条已经带着 bridge.mock 的延迟 (装进游戏后是真实往返), 再叠一层
    // 只会让接线前后的手感对不上。
    await sleep(plannedLatencyMs())
    return (await invokePlanned(action, payload)) as MockResultOf<A>
  }
  return (await delegateReal(action, payload)) as MockResultOf<A>
}
