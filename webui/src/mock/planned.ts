/**
 * 前端假定契约 (PLANNED) —— 本文件所有类型都是前端现在发明的形状, 不是服务端契约。
 * 接线时必须以 Java 实现为准重写, 不允许反过来让 Java 迁就本文件。
 *
 * 为什么要单独一个文件 (而不是往 ../lib/types.ts 里塞):
 * lib/types.ts 里的 action 是逐字读 Java 源码抄下来的真契约, 每个字段都标了落点; 本文件里的东西
 * 一行 Java 都不对应。两者一旦混在一处, 半年后没人分得清哪个字段是"服务端真的会返回"、哪个是"当初编的"。
 * 故物理隔离三件套: 独立文件 + 全部类型名带 Planned 前缀 + 每个 action 标注接线清单行号。
 *
 * 接线核销流程 (逐条):
 *   1. 每个 action 上方注明它对应 docs/WebUI_Frontend_Wiring_Checklist.md 第三章接线总表的哪一行;
 *   2. 后端把该行落地后, 按 Java 实现重写这里的 payload/result;
 *   3. 重写完成的 action 从 PLANNED_ACTIONS 与 PlannedContractMap 移除, 类型搬进 lib/types.ts,
 *      handlers.ts 里对应的实现改为转调 lib/bridge 的 call()。三步做完才算这一行核销掉。
 *
 * 本轮核销后只剩 H 组两条 (shop.catalog / shop.detail): 它们的服务端在 WOK-ChestShop 跨仓, 本轮不做。
 * 另有一条 shop.buy 是**直接删除**而不是核销 —— 已拍板"系统商店只做浏览比价, 不做隔空下单",
 * 它不会有后端实现 (ShopTransaction.buy 只接受玩家物理点击真实告示牌的路径, 内嵌 reach/tamper/冷却校验)。
 *
 * 三条全局约定 (免得每个类型重复解释):
 *   - displayName: 真服大概率只回翻译键 (descriptionId) 交给 client.i18n 解, 但 mock 阶段那些键没有 lang
 *     条目, 面板会显示成一串 job.miningdim.miner。故 planned 结构一律直给中文 displayName; 接线时凡是能
 *     改走翻译键的都应改掉, 这个字段本身就是"尚未接线"的标记。
 *   - 时间: 一律 epoch millis, 与真契约的 createdAt / acquiredAt 同口径; 不用相对秒数, 也不用 Date 对象
 *     (JSON 里不存在 Date, 用了就会在接线时炸)。
 *   - 可选性: planned 结构一律不用 `?:`, 缺值用 `| null`。真契约里 `?:` 与 `| null` 的分野来自 Gson 的
 *     serializeNulls 配置 (见 lib/types.ts 文件头), 那是读码读出来的事实; 这里没有对应事实可依, 与其瞎猜
 *     一个键是缺席还是 null, 不如全用 null 并在接线时按 Java 逐个改正。
 */

/** 不吃任何字段的 planned action 用它占位 (等价 `{}`, 但不触发 no-empty-object-type)。 */
export type PlannedEmptyPayload = Record<string, never>

/** 三维坐标。当前只剩告示牌商店在用 (矿洞落点/军械台方块位已随各自 action 核销, 改用真契约的 WebUiBlockPos)。 */
export interface PlannedBlockPos {
  x: number
  y: number
  z: number
}

// ============================================================
// H 组 · 系统商店 (WOK-ChestShop 跨仓)
// ============================================================

/** H1 shop.catalog / H2 shop.detail 共用的告示牌商店条目。 */
export interface PlannedShopEntry {
  shopId: string
  /** 告示牌所在维度 id。真服 AdminShopRegistry 按 BlockPos 存且逐维度隔离, 故维度必须带上。 */
  dimension: string
  pos: PlannedBlockPos
  itemId: string
  descriptionId: string
  /** 玩家买入价; 该店不收买单时 null。 */
  buyPrice: number | null
  /** 玩家卖出价; 该店不收卖单时 null。 */
  sellPrice: number | null
  /** 系统店无限库存时 null。 */
  stock: number | null
}

/** H1 shop.catalog 回执。 */
export interface PlannedShopCatalogResult {
  shops: PlannedShopEntry[]
}

/** H2 shop.detail 入参。 */
export interface PlannedShopDetailPayload {
  shopId: string
}

/** H2 + H4 shop.detail 回执: 单店详情 + 同物品跨店比价 (真服只有正向索引, 比价是要新建的那部分)。 */
export interface PlannedShopDetailResult {
  shop: PlannedShopEntry
  comparable: PlannedShopEntry[]
}

// ============================================================
// 契约表 (与 lib/bridge.ts 的 WebUiContractMap 同结构, 但两张表永不合并)
// ============================================================

/**
 * planned action 名 -> {payload, result}。
 *
 * 这里的名字都是"后端将来大概率会叫的名字"(接线清单里写的那个)。
 * 唯一刻意避名的那条 (market.transactions) 已随 W2 核销归位到真契约 market.history, 不再出现在本表。
 */
export type PlannedContractMap = {
  'shop.catalog': { payload: PlannedEmptyPayload; result: PlannedShopCatalogResult }
  'shop.detail': { payload: PlannedShopDetailPayload; result: PlannedShopDetailResult }
}

export type PlannedActionName = keyof PlannedContractMap
export type PlannedPayloadOf<A extends PlannedActionName> = PlannedContractMap[A]['payload']
export type PlannedResultOf<A extends PlannedActionName> = PlannedContractMap[A]['result']

/**
 * 全部 planned action 名的运行期清单。
 *
 * 用途有二: handlers 据此判"这个 action 走内存世界还是转调真桥"; 接线时它就是核销进度表 ——
 * 数组变短一条, 代表后端真落地了一行。按接线清单的分组顺序排列, 便于与文档对照。
 */
export const PLANNED_ACTIONS = ['shop.catalog', 'shop.detail'] as const

/**
 * 编译期双向核对 (同 lib/bridge.ts 的 AssertContractCoverage): 清单数组与契约表任一方少一条或多一条,
 * 本类型即坍成 never, 下面那行赋值随之报错。两张手工表最容易悄悄脱节, 这道锁把脱节提到编译期。
 */
type AssertPlannedCoverage = [
  Exclude<PlannedActionName, (typeof PLANNED_ACTIONS)[number]>,
] extends [never]
  ? [Exclude<(typeof PLANNED_ACTIONS)[number], PlannedActionName>] extends [never]
    ? true
    : never
  : never

export const PLANNED_ACTIONS_COVER_CONTRACT: AssertPlannedCoverage = true
